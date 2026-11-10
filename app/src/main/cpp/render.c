/*
 * The guest's screen on a SurfaceView, straight from its RAM.
 *
 * QEMU keeps guest RAM in a memfd (memory-backend-memfd) and the iOS 16 guest scans its
 * framebuffer out of a fixed place in it (ramfb; qemu-sweet.py FB_BASE).  We open that memfd
 * through /proc/<qemu>/fd (same uid), map the framebuffer read-only and, once per display
 * frame, upload it to a GLES texture when it changed.  No copy through QEMU, no screendump.
 */
#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <linux/falloc.h>

#define TAG "asoft-render"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static const uint8_t *fb;
static size_t fb_map_len;
static int fb_w, fb_h, fb_stride;
static uint64_t last_sum;

static ANativeWindow *win;
static EGLDisplay dpy = EGL_NO_DISPLAY;
static EGLSurface surf = EGL_NO_SURFACE;
static EGLContext ctx = EGL_NO_CONTEXT;
static GLuint prog, tex, vbo;
static GLint u_scale;
static int surf_w, surf_h;
static int need_upload = 1;
static uint64_t frames_drawn, frames_uploaded;

/*
 * The app owns the guest's RAM: an app may not look into its child's /proc/<pid>/fd
 * (SELinux), so the memfd is made here, handed to QEMU by inheritance and given to it as
 * memory-backend-file,mem-path=/proc/self/fd/<n>.  The framebuffer is mapped from our own fd.
 */
static int ram_fd = -1;
static off_t ram_size;

JNIEXPORT jint JNICALL
Java_com_asoft_ios_Render_createGuestRam(JNIEnv *env, jclass cls, jlong size)
{
    if (ram_fd >= 0) {
        close(ram_fd);
    }
    ram_fd = syscall(__NR_memfd_create, "guest-ram", 0);     /* no CLOEXEC: QEMU inherits it */
    if (ram_fd < 0 || ftruncate(ram_fd, size) < 0) {
        LOGE("guest RAM memfd: %s", strerror(errno));
        return -1;
    }
    ram_size = size;
    return ram_fd;
}

/*
 * QEMU has ended: the guest's pages go back to the phone now, not at the next start. This app holds
 * the memfd, so after iOS 16's Save and close they stayed allocated (3.4 GB of Shmem measured on
 * 2026-11-08), and lmkd killed the app, its foreground setup included, at the next squeeze. A hole
 * punched over the whole file frees them and keeps its size: a framebuffer still mapped reads zeros
 * (a truncate would make it fault).
 */
JNIEXPORT jboolean JNICALL
Java_com_asoft_ios_Render_releaseGuestRam(JNIEnv *env, jclass cls)
{
    if (ram_fd < 0 || ram_size <= 0) {
        return JNI_FALSE;
    }
    if (fallocate(ram_fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, 0, ram_size) < 0) {
        LOGE("guest RAM release: %s", strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* No guest RAM of ours any more (iOS 26 keeps its own): the pages go back to the phone. */
JNIEXPORT void JNICALL
Java_com_asoft_ios_Render_closeGuestRam(JNIEnv *env, jclass cls)
{
    if (ram_fd >= 0) {
        close(ram_fd);
        ram_fd = -1;
    }
}

/*
 * iOS 26 (vresearch, pvg=reims) draws on the host GPU and reads each frame back into the
 * console's surface: QEMU puts that surface in this memfd (ASOFT_FRAME_FD), so its frames are
 * mapped here exactly as iOS 16's framebuffer is from its RAM.
 */
static int frame_fd = -1;

JNIEXPORT jint JNICALL
Java_com_asoft_ios_Render_createFrameMemory(JNIEnv *env, jclass cls, jlong size)
{
    if (frame_fd >= 0) {
        close(frame_fd);
    }
    frame_fd = syscall(__NR_memfd_create, "guest-frame", 0);     /* no CLOEXEC: QEMU inherits it */
    if (frame_fd < 0 || ftruncate(frame_fd, size) < 0) {
        LOGE("frame memfd: %s", strerror(errno));
        return -1;
    }
    return frame_fd;
}

/* @len bytes of the frame memfd from @off into @out (the setup's look at the screen); bytes read, or -1 */
JNIEXPORT jint JNICALL
Java_com_asoft_ios_Render_readFrame(JNIEnv *env, jclass cls, jlong off, jbyteArray out, jint len)
{
    if (frame_fd < 0) {
        return -1;
    }
    jbyte *b = (*env)->GetByteArrayElements(env, out, NULL);
    ssize_t n = pread(frame_fd, b, (size_t)len, (off_t)off);
    (*env)->ReleaseByteArrayElements(env, out, b, 0);
    return (jint)n;
}

/*
 * fork + exec of QEMU with @argv and @envp, stdout and stderr to @log, keeping ram_fd.
 * Java's ProcessBuilder closes every descriptor but 0-2 in the child, hence this.
 */
JNIEXPORT jint JNICALL
Java_com_asoft_ios_Render_spawn(JNIEnv *env, jclass cls, jobjectArray jargv, jobjectArray jenvp, jstring jlog)
{
    int na = (*env)->GetArrayLength(env, jargv), ne = (*env)->GetArrayLength(env, jenvp);
    char **argv = calloc(na + 1, sizeof(char *)), **envp = calloc(ne + 1, sizeof(char *));
    for (int i = 0; i < na; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jargv, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
    }
    for (int i = 0; i < ne; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jenvp, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        envp[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
    }
    const char *lc = (*env)->GetStringUTFChars(env, jlog, NULL);
    int log = open(lc, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    (*env)->ReleaseStringUTFChars(env, jlog, lc);
    int null = open("/dev/null", O_RDONLY | O_CLOEXEC);
    pid_t pid = fork();
    if (pid == 0) {
        /* async-signal-safe calls only until exec: the JVM had threads */
        dup2(null, 0);
        dup2(log, 1);
        dup2(log, 2);
        setsid();
        execve(argv[0], argv, envp);
        _exit(127);
    }
    close(log);
    close(null);
    for (int i = 0; i < na; i++) {
        free(argv[i]);
    }
    for (int i = 0; i < ne; i++) {
        free(envp[i]);
    }
    free(argv);
    free(envp);
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
    }
    return pid;
}

/*
 * How the last few children ended (their raw wait status), kept by the alive() that reaped them: many
 * threads ask alive() and any of them may be the one that reaps QEMU, while the crash report needs to know
 * whether it ended with an exit code or a signal.
 */
#define ENDS 8
static struct { int pid, status; } ends[ENDS];
static int ends_next;
static pthread_mutex_t ends_lock = PTHREAD_MUTEX_INITIALIZER;

/* true while @pid lives; reaps it when it has ended, and keeps how it ended for endStatus() */
JNIEXPORT jboolean JNICALL
Java_com_asoft_ios_Render_alive(JNIEnv *env, jclass cls, jint pid)
{
    int st;
    if (pid <= 0) {
        return JNI_FALSE;
    }
    pid_t r = waitpid(pid, &st, WNOHANG);
    if (r == pid) {
        pthread_mutex_lock(&ends_lock);
        ends[ends_next].pid = pid;
        ends[ends_next].status = st;
        ends_next = (ends_next + 1) % ENDS;
        pthread_mutex_unlock(&ends_lock);
    }
    return r == 0;
}

/* the raw wait status @pid ended with (the newest one kept for it), or -1 when it was not seen ending */
JNIEXPORT jint JNICALL
Java_com_asoft_ios_Render_endStatus(JNIEnv *env, jclass cls, jint pid)
{
    int st = -1;
    pthread_mutex_lock(&ends_lock);
    for (int i = 1; i <= ENDS; i++) {
        int k = (ends_next - i + ENDS) % ENDS;
        if (pid > 0 && ends[k].pid == pid) {
            st = ends[k].status;
            break;
        }
    }
    pthread_mutex_unlock(&ends_lock);
    return st;
}

JNIEXPORT void JNICALL
Java_com_asoft_ios_Render_terminate(JNIEnv *env, jclass cls, jint pid)
{
    if (pid > 0) {
        kill(pid, SIGTERM);
    }
}

JNIEXPORT void JNICALL
Java_com_asoft_ios_Render_forceKill(JNIEnv *env, jclass cls, jint pid)
{
    if (pid > 0) {
        kill(pid, SIGKILL);
    }
}

/* the guest RAM memfd of process @pid: the largest memfd it holds open */
static int open_guest_ram(int pid, char *name, size_t namelen)
{
    if (ram_fd >= 0) {
        snprintf(name, namelen, "guest-ram (ours, fd %d)", ram_fd);
        return dup(ram_fd);
    }
    char dir[64], path[96], link[256];
    snprintf(dir, sizeof(dir), "/proc/%d/fd", pid);
    DIR *d = opendir(dir);
    if (!d) {
        LOGE("opendir %s: %s", dir, strerror(errno));
        return -1;
    }
    int best = -1;
    off_t best_size = 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') {
            continue;
        }
        snprintf(path, sizeof(path), "%s/%s", dir, e->d_name);
        ssize_t n = readlink(path, link, sizeof(link) - 1);
        if (n <= 0) {
            continue;
        }
        link[n] = 0;
        if (strncmp(link, "/memfd:", 7)) {
            continue;
        }
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }
        struct stat st;
        if (fstat(fd, &st) == 0 && st.st_size > best_size) {
            if (best >= 0) {
                close(best);
            }
            best = fd;
            best_size = st.st_size;
            snprintf(name, namelen, "%s", link);
        } else {
            close(fd);
        }
    }
    closedir(d);
    return best;
}

JNIEXPORT jstring JNICALL
Java_com_asoft_ios_Render_openFramebuffer(JNIEnv *env, jclass cls, jint pid, jlong offset,
                                          jint width, jint height, jint stride)
{
    char name[256] = "";
    int fd;

    if (offset < 0) {           /* iOS 26: the frame memfd, from its start */
        if (frame_fd < 0) {
            return (*env)->NewStringUTF(env, "no frame memfd");
        }
        snprintf(name, sizeof(name), "guest-frame (ours, fd %d)", frame_fd);
        fd = dup(frame_fd);
        offset = 0;
    } else {
        fd = open_guest_ram(pid, name, sizeof(name));
    }
    if (fd < 0) {
        char msg[160];
        snprintf(msg, sizeof(msg), "no guest RAM memfd in /proc/%d/fd (%s)", pid, strerror(errno));
        return (*env)->NewStringUTF(env, msg);
    }
    size_t len = (size_t)stride * height;
    void *p = mmap(NULL, len, PROT_READ, MAP_SHARED, fd, (off_t)offset);
    close(fd);
    if (p == MAP_FAILED) {
        char msg[300];
        snprintf(msg, sizeof(msg), "mmap of %s: %s", name, strerror(errno));
        return (*env)->NewStringUTF(env, msg);
    }
    if (fb) {
        munmap((void *)fb, fb_map_len);
    }
    fb = p;
    fb_map_len = len;
    fb_w = width;
    fb_h = height;
    fb_stride = stride;
    need_upload = 1;
    LOGI("framebuffer %dx%d from %s at %#llx", width, height, name, (unsigned long long)offset);
    return NULL;
}

static GLuint shader(GLenum type, const char *src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGE("shader: %s", log);
    }
    return s;
}

static void gl_setup(void)
{
    static const char *vs =
        "#version 300 es\n"
        "layout(location = 0) in vec2 pos;\n"
        "uniform vec2 scale;\n"
        "out vec2 uv;\n"
        "void main() { uv = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5); gl_Position = vec4(pos * scale, 0.0, 1.0); }\n";
    /* the guest writes B, G, R, X bytes: the texture's swizzle puts them right, alpha is 1 */
    static const char *fs =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec2 uv;\n"
        "uniform sampler2D t;\n"
        "out vec4 color;\n"
        "void main() { color = texture(t, uv); }\n";
    prog = glCreateProgram();
    glAttachShader(prog, shader(GL_VERTEX_SHADER, vs));
    glAttachShader(prog, shader(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(prog);
    u_scale = glGetUniformLocation(prog, "scale");
    static const float quad[] = { -1, -1, 1, -1, -1, 1, 1, 1 };
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_BLUE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, GL_RED);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE);
    need_upload = 1;
}

static void egl_teardown(void)
{
    if (dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surf != EGL_NO_SURFACE) {
            eglDestroySurface(dpy, surf);
        }
        if (ctx != EGL_NO_CONTEXT) {
            eglDestroyContext(dpy, ctx);
        }
        eglTerminate(dpy);
    }
    dpy = EGL_NO_DISPLAY;
    surf = EGL_NO_SURFACE;
    ctx = EGL_NO_CONTEXT;
    if (win) {
        ANativeWindow_release(win);
        win = NULL;
    }
}

/* called on the render thread: every GL call below happens there */
JNIEXPORT jboolean JNICALL
Java_com_asoft_ios_Render_attach(JNIEnv *env, jclass cls, jobject surface)
{
    egl_teardown();
    win = ANativeWindow_fromSurface(env, surface);
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, NULL, NULL);
    const EGLint cfg_attr[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE };
    EGLConfig cfg;
    EGLint n = 0;
    if (!eglChooseConfig(dpy, cfg_attr, &cfg, 1, &n) || n < 1) {
        LOGE("no EGL config");
        return JNI_FALSE;
    }
    surf = eglCreateWindowSurface(dpy, cfg, win, NULL);
    const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (surf == EGL_NO_SURFACE || ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, surf, surf, ctx)) {
        LOGE("EGL surface/context: %#x", eglGetError());
        return JNI_FALSE;
    }
    eglSwapInterval(dpy, 1);
    gl_setup();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_asoft_ios_Render_detach(JNIEnv *env, jclass cls)
{
    egl_teardown();
}

/* a cheap fingerprint: 48 rows spread over the screen, 8 bytes in 64 */
static uint64_t fb_sample(void)
{
    uint64_t sum = 0;
    for (int r = 0; r < 48; r++) {
        const uint64_t *row = (const uint64_t *)(fb + (size_t)(r * (fb_h - 1) / 47) * fb_stride);
        for (int i = 0; i < fb_stride / 8; i += 8) {
            sum = (sum ^ row[i]) * 0x100000001b3ULL;
        }
    }
    return sum;
}

/* one display frame: true when something was drawn */
JNIEXPORT jboolean JNICALL
Java_com_asoft_ios_Render_frame(JNIEnv *env, jclass cls, jint width, jint height)
{
    if (ctx == EGL_NO_CONTEXT || !fb) {
        return JNI_FALSE;
    }
    uint64_t sum = fb_sample();
    int resized = width != surf_w || height != surf_h;
    if (!need_upload && !resized && sum == last_sum) {
        return JNI_FALSE;
    }
    if (need_upload || sum != last_sum) {
        glBindTexture(GL_TEXTURE_2D, tex);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, fb_stride / 4);
        if (need_upload) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fb_w, fb_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, fb);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, fb_w, fb_h, GL_RGBA, GL_UNSIGNED_BYTE, fb);
        }
        need_upload = 0;
        last_sum = sum;
        frames_uploaded++;
    }
    surf_w = width;
    surf_h = height;
    /* fit, keeping the guest's aspect */
    float sx = 1, sy = 1;
    float gw = (float)fb_w / fb_h, sw = (float)width / height;
    if (sw > gw) {
        sx = gw / sw;
    } else {
        sy = sw / gw;
    }
    glViewport(0, 0, width, height);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(prog);
    glUniform2f(u_scale, sx, sy);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    eglSwapBuffers(dpy, surf);
    frames_drawn++;
    return JNI_TRUE;
}

/* the guest is gone: let go of its framebuffer and leave the surface black */
JNIEXPORT void JNICALL
Java_com_asoft_ios_Render_clear(JNIEnv *env, jclass cls)
{
    if (fb) {
        munmap((void *)fb, fb_map_len);
        fb = NULL;
    }
    if (ctx != EGL_NO_CONTEXT) {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(dpy, surf);
    }
}

JNIEXPORT jlong JNICALL
Java_com_asoft_ios_Render_uploads(JNIEnv *env, jclass cls)
{
    return (jlong)frames_uploaded;
}
