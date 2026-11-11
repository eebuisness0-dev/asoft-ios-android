/*
 * The GPU check before iOS 26 and watchOS are downloaded (GpuCheck.java): can this phone's Vulkan driver
 * run reims-vgpu + metal2vulkan as the S25 Ultra's Turnip does? It opens the device, reads what it has,
 * and runs what the guest's GPU work rests on:
 *   - a compute dispatch (Metal's kernels become compute shaders),
 *   - a render pass with a draw (Metal's render passes),
 *   - a buffer over host memory (VK_EXT_external_memory_host: reims hands the guest's RAM to the GPU
 *     without a copy; with Turnip that is the KGSL host-pointer import),
 *   - a sparse buffer bound from pieces of a host import, scattered and out of order (the 256 KiB gather
 *     that keeps a long run under Android's 65,530 mappings, APK-PLAN 6.26),
 *   - (recommended) a sparse LINEAR image bound the same way (heap textures; shadowed without it).
 * It runs in its own process (GpuCheckService, :gpucheck): a driver that crashes takes only that one.
 * Every line goes to the log at once ("key: value"), so a crash leaves what came before it; the last
 * line is "result: pass" or "result: fail".
 *
 * mode 0: the Vulkan loader at @loader (the app's Khronos loader, which takes Turnip's ICD from
 * VK_DRIVER_FILES); mode 1: the system's own loader and driver (libvulkan.so), for the log only.
 */
#define VK_NO_PROTOTYPES
#include <dlfcn.h>
#include <jni.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <vulkan/vulkan.h>

#include "gpucheck-spv.h"

static FILE *out;
static int failed;
static char missing[4096];

static void say(const char *fmt, ...)
{
    va_list a;
    va_start(a, fmt);
    vfprintf(out, fmt, a);
    va_end(a);
    fputc('\n', out);
    fflush(out);
}

/* a requirement the phone lacks: into the "missing:" line and the result */
static void lack(const char *what)
{
    failed = 1;
    say("lacks: %s", what);
    size_t n = strlen(missing);
    snprintf(missing + n, sizeof missing - n, "%s%s", n ? ", " : "", what);
}

static PFN_vkGetInstanceProcAddr gipa;
static PFN_vkGetDeviceProcAddr gdpa;
static VkInstance inst;
static VkDevice dev;

#define IFN(name) PFN_##name name = (PFN_##name)gipa(inst, #name)
#define DFN(name) PFN_##name name = (PFN_##name)gdpa(dev, #name)
#define TRY(what, x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { say("error: %s: VkResult %d", what, r_); return r_; } } while (0)

static int has_ext(const VkExtensionProperties *e, uint32_t n, const char *name)
{
    for (uint32_t i = 0; i < n; i++) {
        if (!strcmp(e[i].extensionName, name)) {
            return 1;
        }
    }
    return 0;
}

/* -- what the device runs on --------------------------------------------------------------------- */

static VkPhysicalDevice pd;
static VkPhysicalDeviceMemoryProperties mp;
static uint32_t gfx_family = UINT32_MAX, sparse_family = UINT32_MAX;
static VkQueue gq, sq;
static VkCommandPool pool;
static VkFence fence;

static uint32_t host_type(uint32_t bits)
{
    for (uint32_t t = 0; t < mp.memoryTypeCount; t++) {
        VkMemoryPropertyFlags f = mp.memoryTypes[t].propertyFlags;
        if ((bits & (1u << t)) && (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) && (f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            return t;
        }
    }
    return UINT32_MAX;
}

static VkResult plain_buffer(VkDeviceSize size, VkBufferUsageFlags use, VkBuffer *b, VkDeviceMemory *m, void **map)
{
    DFN(vkCreateBuffer);
    DFN(vkGetBufferMemoryRequirements);
    DFN(vkAllocateMemory);
    DFN(vkBindBufferMemory);
    DFN(vkMapMemory);
    VkBufferCreateInfo bci = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = size, .usage = use};
    TRY("vkCreateBuffer", vkCreateBuffer(dev, &bci, NULL, b));
    VkMemoryRequirements r;
    vkGetBufferMemoryRequirements(dev, *b, &r);
    VkMemoryAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = r.size,
                               .memoryTypeIndex = host_type(r.memoryTypeBits)};
    if (ai.memoryTypeIndex == UINT32_MAX) {
        say("error: no host-visible coherent memory for a buffer");
        return VK_ERROR_FEATURE_NOT_PRESENT;
    }
    TRY("vkAllocateMemory", vkAllocateMemory(dev, &ai, NULL, m));
    TRY("vkBindBufferMemory", vkBindBufferMemory(dev, *b, *m, 0));
    TRY("vkMapMemory", vkMapMemory(dev, *m, 0, size, 0, map));
    return VK_SUCCESS;
}

static VkCommandBuffer begin(void)
{
    DFN(vkAllocateCommandBuffers);
    DFN(vkBeginCommandBuffer);
    VkCommandBufferAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = pool,
                                      .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    VkCommandBuffer cb = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(dev, &ai, &cb) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                   .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    vkBeginCommandBuffer(cb, &bi);
    return cb;
}

static VkResult submit(VkCommandBuffer cb, const char *what)
{
    DFN(vkEndCommandBuffer);
    DFN(vkQueueSubmit);
    DFN(vkWaitForFences);
    DFN(vkResetFences);
    TRY(what, vkEndCommandBuffer(cb));
    VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cb};
    vkResetFences(dev, 1, &fence);
    TRY(what, vkQueueSubmit(gq, 1, &si, fence));
    TRY(what, vkWaitForFences(dev, 1, &fence, VK_TRUE, 10000000000ull));
    return VK_SUCCESS;
}

static VkShaderModule shader(const uint32_t *code, size_t bytes)
{
    DFN(vkCreateShaderModule);
    VkShaderModuleCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = bytes, .pCode = code};
    VkShaderModule m = VK_NULL_HANDLE;
    VkResult r = vkCreateShaderModule(dev, &ci, NULL, &m);
    if (r != VK_SUCCESS) {
        say("error: vkCreateShaderModule: VkResult %d", r);
    }
    return m;
}

/* -- compute: out[i] = 3i + 1 into @buf (a plain buffer, or one over host memory) ----------------- */

static VkResult compute_into(VkBuffer buf, uint32_t n)
{
    DFN(vkCreateDescriptorSetLayout);
    DFN(vkCreatePipelineLayout);
    DFN(vkCreateComputePipelines);
    DFN(vkCreateDescriptorPool);
    DFN(vkAllocateDescriptorSets);
    DFN(vkUpdateDescriptorSets);
    DFN(vkCmdBindPipeline);
    DFN(vkCmdBindDescriptorSets);
    DFN(vkCmdDispatch);
    DFN(vkCmdPipelineBarrier);
    VkDescriptorSetLayoutBinding b = {.binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .descriptorCount = 1,
                                      .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT};
    VkDescriptorSetLayoutCreateInfo lci = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .bindingCount = 1, .pBindings = &b};
    VkDescriptorSetLayout dsl;
    TRY("vkCreateDescriptorSetLayout", vkCreateDescriptorSetLayout(dev, &lci, NULL, &dsl));
    VkPipelineLayoutCreateInfo pli = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &dsl};
    VkPipelineLayout pl;
    TRY("vkCreatePipelineLayout", vkCreatePipelineLayout(dev, &pli, NULL, &pl));
    VkShaderModule m = shader(spv_comp, sizeof spv_comp);
    if (!m) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkComputePipelineCreateInfo cpi = {.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO, .layout = pl,
                                       .stage = {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                                                 .stage = VK_SHADER_STAGE_COMPUTE_BIT, .module = m, .pName = "main"}};
    VkPipeline p;
    TRY("vkCreateComputePipelines", vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &cpi, NULL, &p));
    VkDescriptorPoolSize ps = {.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .descriptorCount = 1};
    VkDescriptorPoolCreateInfo dpi = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets = 1, .poolSizeCount = 1, .pPoolSizes = &ps};
    VkDescriptorPool dp;
    TRY("vkCreateDescriptorPool", vkCreateDescriptorPool(dev, &dpi, NULL, &dp));
    VkDescriptorSetAllocateInfo dai = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, .descriptorPool = dp,
                                       .descriptorSetCount = 1, .pSetLayouts = &dsl};
    VkDescriptorSet ds;
    TRY("vkAllocateDescriptorSets", vkAllocateDescriptorSets(dev, &dai, &ds));
    VkDescriptorBufferInfo bi = {.buffer = buf, .range = (VkDeviceSize)n * 4};
    VkWriteDescriptorSet w = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = ds, .descriptorCount = 1,
                              .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &bi};
    vkUpdateDescriptorSets(dev, 1, &w, 0, NULL);
    VkCommandBuffer cb = begin();
    if (!cb) {
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 1, &ds, 0, NULL);
    vkCmdDispatch(cb, n / 64, 1, 1);
    VkMemoryBarrier mb = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
                          .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    return submit(cb, "compute");
}

static uint32_t wrong_words(const uint32_t *v, uint32_t n)
{
    uint32_t bad = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (v[i] != i * 3 + 1) {
            if (bad++ < 3) {
                say("  word %u: %#x, want %#x", i, v[i], i * 3 + 1);
            }
        }
    }
    return bad;
}

static int test_compute(void)
{
    uint32_t n = 4096;
    VkBuffer b;
    VkDeviceMemory m;
    void *map;
    if (plain_buffer(n * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, &b, &m, &map) != VK_SUCCESS) {
        return 0;
    }
    memset(map, 0, n * 4);
    if (compute_into(b, n) != VK_SUCCESS) {
        return 0;
    }
    uint32_t bad = wrong_words(map, n);
    say("compute: %u of %u words wrong", bad, n);
    return bad == 0;
}

/* -- render: a 64x64 RGBA8 target, one triangle over it, read back --------------------------------- */

static int test_render(void)
{
    IFN(vkGetPhysicalDeviceFormatProperties);
    DFN(vkCreateImage);
    DFN(vkGetImageMemoryRequirements);
    DFN(vkAllocateMemory);
    DFN(vkBindImageMemory);
    DFN(vkCreateImageView);
    DFN(vkCreateRenderPass);
    DFN(vkCreateFramebuffer);
    DFN(vkCreatePipelineLayout);
    DFN(vkCreateGraphicsPipelines);
    DFN(vkCmdBeginRenderPass);
    DFN(vkCmdBindPipeline);
    DFN(vkCmdDraw);
    DFN(vkCmdEndRenderPass);
    DFN(vkCmdCopyImageToBuffer);
    DFN(vkCmdPipelineBarrier);
    const uint32_t W = 64;
    VkFormat fmt = VK_FORMAT_R8G8B8A8_UNORM;
    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(pd, fmt, &fp);
    if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT)) {
        say("render: RGBA8 is no color attachment");
        return 0;
    }
    VkImageCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D, .format = fmt,
                             .extent = {W, W, 1}, .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
                             .tiling = VK_IMAGE_TILING_OPTIMAL,
                             .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                             .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    VkImage img;
    if (vkCreateImage(dev, &ici, NULL, &img) != VK_SUCCESS) {
        say("error: vkCreateImage (render target)");
        return 0;
    }
    VkMemoryRequirements r;
    vkGetImageMemoryRequirements(dev, img, &r);
    uint32_t t = 0;
    while (t < mp.memoryTypeCount && !(r.memoryTypeBits & (1u << t))) {
        t++;
    }
    VkMemoryAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = r.size, .memoryTypeIndex = t};
    VkDeviceMemory im;
    if (vkAllocateMemory(dev, &ai, NULL, &im) != VK_SUCCESS || vkBindImageMemory(dev, img, im, 0) != VK_SUCCESS) {
        say("error: render target memory");
        return 0;
    }
    VkImageViewCreateInfo vci = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img, .viewType = VK_IMAGE_VIEW_TYPE_2D,
                                 .format = fmt, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    VkImageView view;
    if (vkCreateImageView(dev, &vci, NULL, &view) != VK_SUCCESS) {
        say("error: vkCreateImageView");
        return 0;
    }
    VkAttachmentDescription ad = {.format = fmt, .samples = VK_SAMPLE_COUNT_1_BIT, .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
                                  .storeOp = VK_ATTACHMENT_STORE_OP_STORE, .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                                  .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                                  .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL};
    VkAttachmentReference ar = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sd = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1, .pColorAttachments = &ar};
    VkSubpassDependency dep = {.srcSubpass = 0, .dstSubpass = VK_SUBPASS_EXTERNAL,
                               .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, .dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT,
                               .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
    VkRenderPassCreateInfo rpi = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, .attachmentCount = 1, .pAttachments = &ad,
                                  .subpassCount = 1, .pSubpasses = &sd, .dependencyCount = 1, .pDependencies = &dep};
    VkRenderPass rp;
    if (vkCreateRenderPass(dev, &rpi, NULL, &rp) != VK_SUCCESS) {
        say("error: vkCreateRenderPass");
        return 0;
    }
    VkFramebufferCreateInfo fbi = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = rp, .attachmentCount = 1,
                                   .pAttachments = &view, .width = W, .height = W, .layers = 1};
    VkFramebuffer fb;
    if (vkCreateFramebuffer(dev, &fbi, NULL, &fb) != VK_SUCCESS) {
        say("error: vkCreateFramebuffer");
        return 0;
    }
    VkPipelineLayoutCreateInfo pli = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    VkPipelineLayout pl;
    if (vkCreatePipelineLayout(dev, &pli, NULL, &pl) != VK_SUCCESS) {
        return 0;
    }
    VkShaderModule vs = shader(spv_vert, sizeof spv_vert), fs = shader(spv_frag, sizeof spv_frag);
    if (!vs || !fs) {
        return 0;
    }
    VkPipelineShaderStageCreateInfo st[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vs, .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = fs, .pName = "main"}};
    VkPipelineVertexInputStateCreateInfo vi = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia = {.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
                                                 .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST};
    VkViewport vp = {0, 0, (float)W, (float)W, 0, 1};
    VkRect2D sc = {{0, 0}, {W, W}};
    VkPipelineViewportStateCreateInfo vps = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, .viewportCount = 1,
                                             .pViewports = &vp, .scissorCount = 1, .pScissors = &sc};
    VkPipelineRasterizationStateCreateInfo rs = {.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
                                                 .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE, .lineWidth = 1};
    VkPipelineMultisampleStateCreateInfo ms = {.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
                                               .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState cba = {.colorWriteMask = 0xf};
    VkPipelineColorBlendStateCreateInfo cb = {.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, .attachmentCount = 1,
                                              .pAttachments = &cba};
    VkGraphicsPipelineCreateInfo gpi = {.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .stageCount = 2, .pStages = st,
                                        .pVertexInputState = &vi, .pInputAssemblyState = &ia, .pViewportState = &vps,
                                        .pRasterizationState = &rs, .pMultisampleState = &ms, .pColorBlendState = &cb,
                                        .layout = pl, .renderPass = rp};
    VkPipeline p;
    VkResult pr = vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &gpi, NULL, &p);
    if (pr != VK_SUCCESS) {
        say("error: vkCreateGraphicsPipelines: VkResult %d", pr);
        return 0;
    }
    VkBuffer rb;
    VkDeviceMemory rm;
    void *map;
    if (plain_buffer(W * W * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, &rb, &rm, &map) != VK_SUCCESS) {
        return 0;
    }
    memset(map, 0, W * W * 4);
    VkCommandBuffer c = begin();
    if (!c) {
        return 0;
    }
    VkClearValue clear = {.color = {{1, 0, 0, 1}}};
    VkRenderPassBeginInfo rbi = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = rp, .framebuffer = fb,
                                 .renderArea = {{0, 0}, {W, W}}, .clearValueCount = 1, .pClearValues = &clear};
    vkCmdBeginRenderPass(c, &rbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, p);
    vkCmdDraw(c, 3, 1, 0, 0);
    vkCmdEndRenderPass(c);
    VkBufferImageCopy bic = {.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, .imageExtent = {W, W, 1}};
    vkCmdCopyImageToBuffer(c, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rb, 1, &bic);
    VkMemoryBarrier mb = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                          .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    if (submit(c, "render") != VK_SUCCESS) {
        return 0;
    }
    /* (0.25, 0.5, 0.75, 1) in UNORM8: 64, 128, 191, 255 (+-1 for the device's rounding) */
    const uint8_t *px = map;
    uint32_t bad = 0;
    for (uint32_t i = 0; i < W * W; i++) {
        const uint8_t *q = px + i * 4;
        if (abs(q[0] - 64) > 1 || abs(q[1] - 128) > 1 || abs(q[2] - 191) > 1 || q[3] != 255) {
            if (bad++ < 3) {
                say("  pixel %u: %u %u %u %u", i, q[0], q[1], q[2], q[3]);
            }
        }
    }
    say("render: %u of %u pixels wrong", bad, W * W);
    return bad == 0;
}

/* -- host memory: a buffer over an mmap (VK_EXT_external_memory_host), written by the GPU ---------- */

static size_t host_align = 4096;

static int import_host(void *host, size_t size, uint32_t want_bits, VkDeviceMemory *m)
{
    DFN(vkGetMemoryHostPointerPropertiesEXT);
    DFN(vkAllocateMemory);
    if (!vkGetMemoryHostPointerPropertiesEXT) {
        say("error: no vkGetMemoryHostPointerPropertiesEXT");
        return 0;
    }
    VkMemoryHostPointerPropertiesEXT hp = {.sType = VK_STRUCTURE_TYPE_MEMORY_HOST_POINTER_PROPERTIES_EXT};
    VkResult r = vkGetMemoryHostPointerPropertiesEXT(dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, host, &hp);
    if (r != VK_SUCCESS) {
        say("error: vkGetMemoryHostPointerPropertiesEXT: VkResult %d", r);
        return 0;
    }
    uint32_t bits = hp.memoryTypeBits & want_bits;
    say("host import: memory types %#x (wanted %#x)", hp.memoryTypeBits, want_bits);
    uint32_t t = 0;
    while (t < 32 && !(bits & (1u << t))) {
        t++;
    }
    if (t == 32) {
        say("error: no memory type both the resource and the host import take");
        return 0;
    }
    VkImportMemoryHostPointerInfoEXT imp = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT,
                                            .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, .pHostPointer = host};
    VkMemoryAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &imp, .allocationSize = size, .memoryTypeIndex = t};
    r = vkAllocateMemory(dev, &ai, NULL, m);
    if (r != VK_SUCCESS) {
        say("error: vkAllocateMemory (host import of %zu bytes): VkResult %d", size, r);
        return 0;
    }
    return 1;
}

static int test_host(void)
{
    DFN(vkCreateBuffer);
    DFN(vkGetBufferMemoryRequirements);
    DFN(vkBindBufferMemory);
    uint32_t n = 1 << 20;                  /* 4 MiB */
    size_t size = (size_t)n * 4;
    uint32_t *host = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (host == MAP_FAILED) {
        say("error: mmap");
        return 0;
    }
    memset(host, 0, size);
    VkExternalMemoryBufferCreateInfo eb = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
                                           .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT};
    VkBufferCreateInfo bci = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &eb, .size = size,
                              .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT};
    VkBuffer b;
    VkResult r = vkCreateBuffer(dev, &bci, NULL, &b);
    if (r != VK_SUCCESS) {
        say("error: vkCreateBuffer (external host): VkResult %d", r);
        return 0;
    }
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(dev, b, &mr);
    VkDeviceMemory m;
    if (!import_host(host, size, mr.memoryTypeBits, &m)) {
        return 0;
    }
    r = vkBindBufferMemory(dev, b, m, 0);
    if (r != VK_SUCCESS) {
        say("error: vkBindBufferMemory (host import): VkResult %d", r);
        return 0;
    }
    if (compute_into(b, n) != VK_SUCCESS) {
        return 0;
    }
    uint32_t bad = wrong_words(host, n);
    say("host memory: %u of %u words the GPU wrote into the phone's own pages wrong", bad, n);
    return bad == 0;
}

/* -- sparse: a buffer gathered from scattered pieces of one host import, read back by a copy -------- */

static VkResult bind_sparse(const VkBindSparseInfo *bsi)
{
    DFN(vkQueueBindSparse);
    DFN(vkWaitForFences);
    DFN(vkResetFences);
    vkResetFences(dev, 1, &fence);
    TRY("vkQueueBindSparse", vkQueueBindSparse(sq, 1, bsi, fence));
    TRY("vkWaitForFences (bind)", vkWaitForFences(dev, 1, &fence, VK_TRUE, 10000000000ull));
    return VK_SUCCESS;
}

static uint8_t *sparse_host;
static const size_t SPARSE_BIG = 32 << 20;
static VkDeviceMemory sparse_mem;

static int sparse_import(uint32_t want_bits)
{
    if (sparse_host) {
        return 1;
    }
    sparse_host = mmap(NULL, SPARSE_BIG, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (sparse_host == MAP_FAILED) {
        sparse_host = NULL;
        say("error: mmap");
        return 0;
    }
    for (size_t i = 0; i < SPARSE_BIG; i += 4) {
        *(uint32_t *)(sparse_host + i) = (uint32_t)(i / 4096);     /* each 4 KiB holds its own number */
    }
    if (!import_host(sparse_host, SPARSE_BIG, want_bits, &sparse_mem)) {
        munmap(sparse_host, SPARSE_BIG);
        sparse_host = NULL;
        return 0;
    }
    return 1;
}

static int test_sparse_buffer(void)
{
    DFN(vkCreateBuffer);
    DFN(vkGetBufferMemoryRequirements);
    DFN(vkCmdCopyBuffer);
    DFN(vkCmdPipelineBarrier);
    VkExternalMemoryBufferCreateInfo eb = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
                                           .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT};
    VkDeviceSize size = 1 << 20;
    VkBufferCreateInfo bci = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &eb, .size = size,
                              .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                              .flags = VK_BUFFER_CREATE_SPARSE_BINDING_BIT};
    VkBuffer sb;
    VkResult r = vkCreateBuffer(dev, &bci, NULL, &sb);
    if (r != VK_SUCCESS) {
        say("error: vkCreateBuffer (sparse): VkResult %d", r);
        return 0;
    }
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(dev, sb, &mr);
    say("sparse buffer: block %llu bytes, memory types %#x", (unsigned long long)mr.alignment, mr.memoryTypeBits);
    if (mr.alignment > (1 << 16) || SPARSE_BIG % mr.alignment) {
        say("error: sparse block of %llu bytes", (unsigned long long)mr.alignment);
        return 0;
    }
    if (!sparse_import(mr.memoryTypeBits)) {
        return 0;
    }
    VkDeviceSize blk = mr.alignment, pieces = size / blk, inbig = SPARSE_BIG / blk;
    VkSparseMemoryBind *binds = calloc(pieces, sizeof *binds);
    for (VkDeviceSize i = 0; i < pieces; i++) {
        /* piece i from piece (37i + 11) mod N of the import: scattered and out of order */
        binds[i] = (VkSparseMemoryBind){.resourceOffset = i * blk, .size = blk, .memory = sparse_mem,
                                        .memoryOffset = ((37ull * i + 11) % inbig) * blk};
    }
    VkSparseBufferMemoryBindInfo bbi = {.buffer = sb, .bindCount = (uint32_t)pieces, .pBinds = binds};
    VkBindSparseInfo bsi = {.sType = VK_STRUCTURE_TYPE_BIND_SPARSE_INFO, .bufferBindCount = 1, .pBufferBinds = &bbi};
    if (bind_sparse(&bsi) != VK_SUCCESS) {
        return 0;
    }
    VkBuffer db;
    VkDeviceMemory dm;
    void *map;
    if (plain_buffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, &db, &dm, &map) != VK_SUCCESS) {
        return 0;
    }
    memset(map, 0xff, size);
    VkCommandBuffer c = begin();
    VkBufferCopy cp = {.size = size};
    vkCmdCopyBuffer(c, sb, db, 1, &cp);
    VkMemoryBarrier mb = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                          .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    if (submit(c, "sparse copy") != VK_SUCCESS) {
        return 0;
    }
    uint32_t bad = 0;
    for (VkDeviceSize i = 0; i < pieces; i++) {
        uint32_t want = (uint32_t)((((37ull * i + 11) % inbig) * blk) / 4096);
        uint32_t got = *(uint32_t *)((uint8_t *)map + i * blk);
        if (got != want && bad++ < 3) {
            say("  piece %llu: %u, want %u", (unsigned long long)i, got, want);
        }
    }
    free(binds);
    say("sparse gather: %u of %llu pieces wrong", bad, (unsigned long long)pieces);
    return bad == 0;
}

/* recommended: a sparse LINEAR BGRA8 image, its opaque memory from scattered pieces of the host import */
static int test_sparse_image(void)
{
    DFN(vkCreateImage);
    DFN(vkGetImageMemoryRequirements);
    DFN(vkCmdCopyImageToBuffer);
    DFN(vkCmdPipelineBarrier);
    DFN(vkGetImageSubresourceLayout);
    const uint32_t W = 256, H = 64;
    VkExternalMemoryImageCreateInfo ei = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
                                          .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT};
    VkImageCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &ei, .flags = VK_IMAGE_CREATE_SPARSE_BINDING_BIT,
                             .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_B8G8R8A8_UNORM, .extent = {W, H, 1},
                             .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
                             .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                             .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    VkImage img;
    VkResult r = vkCreateImage(dev, &ici, NULL, &img);
    if (r != VK_SUCCESS) {
        say("sparse image: vkCreateImage (sparse, linear): VkResult %d", r);
        return 0;
    }
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(dev, img, &mr);
    VkImageSubresource sub = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0};
    VkSubresourceLayout lay;
    vkGetImageSubresourceLayout(dev, img, &sub, &lay);
    say("sparse image: %llu bytes, block %llu, row pitch %llu", (unsigned long long)mr.size, (unsigned long long)mr.alignment,
        (unsigned long long)lay.rowPitch);
    if (!sparse_import(mr.memoryTypeBits) || mr.alignment > (1 << 16) || SPARSE_BIG % mr.alignment) {
        return 0;
    }
    VkDeviceSize blk = mr.alignment, pieces = (mr.size + blk - 1) / blk, inbig = SPARSE_BIG / blk;
    VkSparseMemoryBind *binds = calloc(pieces, sizeof *binds);
    for (VkDeviceSize i = 0; i < pieces; i++) {
        binds[i] = (VkSparseMemoryBind){.resourceOffset = i * blk, .size = blk, .memory = sparse_mem,
                                        .memoryOffset = ((53ull * i + 7) % inbig) * blk};
    }
    VkSparseImageOpaqueMemoryBindInfo ob = {.image = img, .bindCount = (uint32_t)pieces, .pBinds = binds};
    VkBindSparseInfo bsi = {.sType = VK_STRUCTURE_TYPE_BIND_SPARSE_INFO, .imageOpaqueBindCount = 1, .pImageOpaqueBinds = &ob};
    if (bind_sparse(&bsi) != VK_SUCCESS) {
        return 0;
    }
    VkBuffer db;
    VkDeviceMemory dm;
    void *map;
    if (plain_buffer(W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, &db, &dm, &map) != VK_SUCCESS) {
        return 0;
    }
    VkCommandBuffer c = begin();
    /* UNDEFINED -> GENERAL: Turnip keeps a linear image's bytes (APK-PLAN 6.19's probe); the copy reads them */
    VkImageMemoryBarrier ib = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
                               .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                               .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                               .image = img, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &ib);
    VkBufferImageCopy bic = {.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, .imageExtent = {W, H, 1}};
    vkCmdCopyImageToBuffer(c, img, VK_IMAGE_LAYOUT_GENERAL, db, 1, &bic);
    VkMemoryBarrier mb = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                          .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    if (submit(c, "sparse image copy") != VK_SUCCESS) {
        return 0;
    }
    /* each texel: the number of the 4 KiB of the import its byte offset in the image's memory falls in */
    uint32_t bad = 0;
    for (uint32_t y = 0; y < H; y++) {
        for (uint32_t x = 0; x < W; x++) {
            VkDeviceSize off = lay.offset + y * lay.rowPitch + x * 4;
            VkDeviceSize piece = off / blk, src = ((53ull * piece + 7) % inbig) * blk + off % blk;
            uint32_t want = (uint32_t)(src / 4096), got = ((uint32_t *)map)[y * W + x];
            if (got != want && bad++ < 3) {
                say("  texel %u,%u: %u, want %u", x, y, got, want);
            }
        }
    }
    free(binds);
    say("sparse image: %u of %u texels wrong", bad, W * H);
    return bad == 0;
}

/* -- the check --------------------------------------------------------------------------------- */

static const char *vendor(uint32_t id)
{
    switch (id) {
    case 0x5143: return "Qualcomm";
    case 0x13b5: return "ARM";
    case 0x1010: return "Imagination";
    case 0x144d: return "Samsung";
    case 0x10de: return "NVIDIA";
    case 0x1002: return "AMD";
    case 0x8086: return "Intel";
    case 0x10005: return "Mesa";
    default: return "?";
    }
}

static int check(const char *loader)
{
    void *lib = dlopen(loader, RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        say("error: cannot load the Vulkan loader %s: %s", loader, dlerror());
        lack("a Vulkan loader");
        return 2;
    }
    gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!gipa) {
        lack("vkGetInstanceProcAddr");
        return 2;
    }
    PFN_vkEnumerateInstanceVersion eiv = (PFN_vkEnumerateInstanceVersion)gipa(NULL, "vkEnumerateInstanceVersion");
    uint32_t iv = VK_API_VERSION_1_0;
    if (eiv) {
        eiv(&iv);
    }
    say("instance: Vulkan %u.%u.%u", VK_API_VERSION_MAJOR(iv), VK_API_VERSION_MINOR(iv), VK_API_VERSION_PATCH(iv));
    PFN_vkCreateInstance ci = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "asoft-gpucheck",
                             .apiVersion = iv >= VK_API_VERSION_1_3 ? VK_API_VERSION_1_3 : VK_API_VERSION_1_2};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    VkResult r = ci(&ici, NULL, &inst);
    if (r != VK_SUCCESS) {
        say("error: vkCreateInstance: VkResult %d", r);
        lack("a Vulkan 1.2 instance");
        return 2;
    }
    IFN(vkEnumeratePhysicalDevices);
    IFN(vkGetPhysicalDeviceProperties2);
    IFN(vkGetPhysicalDeviceFeatures2);
    IFN(vkGetPhysicalDeviceMemoryProperties);
    IFN(vkGetPhysicalDeviceQueueFamilyProperties);
    IFN(vkEnumerateDeviceExtensionProperties);
    IFN(vkCreateDevice);
    gdpa = (PFN_vkGetDeviceProcAddr)gipa(inst, "vkGetDeviceProcAddr");
    uint32_t n = 0;
    r = vkEnumeratePhysicalDevices(inst, &n, NULL);
    say("devices: %u (VkResult %d)", n, r);
    if (!n) {
        lack("a Vulkan device");
        return 2;
    }
    VkPhysicalDevice all[8];
    if (n > 8) {
        n = 8;
    }
    vkEnumeratePhysicalDevices(inst, &n, all);
    pd = all[0];
    VkPhysicalDeviceDriverProperties drv = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
    VkPhysicalDeviceExternalMemoryHostPropertiesEXT hostp = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_MEMORY_HOST_PROPERTIES_EXT,
                                                             .pNext = &drv};
    VkPhysicalDeviceProperties2 p2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &hostp};
    vkGetPhysicalDeviceProperties2(pd, &p2);
    for (char *c = drv.driverInfo; *c; c++) {
        if (*c == 10 || *c == 13) {
            *c = ' ';          /* Qualcomm's spans lines */
        }
    }
    VkPhysicalDeviceProperties *p = &p2.properties;
    say("gpu: %s", p->deviceName);
    say("vendor: %#x (%s), device %#x", p->vendorID, vendor(p->vendorID), p->deviceID);
    say("device api: Vulkan %u.%u.%u", VK_API_VERSION_MAJOR(p->apiVersion), VK_API_VERSION_MINOR(p->apiVersion),
        VK_API_VERSION_PATCH(p->apiVersion));
    say("driver: %s, %s (driver id %d, version %#x, conformance %u.%u.%u.%u)", drv.driverName, drv.driverInfo, drv.driverID,
        p->driverVersion, drv.conformanceVersion.major, drv.conformanceVersion.minor, drv.conformanceVersion.subminor,
        drv.conformanceVersion.patch);
    say("limits: maxMemoryAllocationCount %u, maxStorageBufferRange %u, maxComputeWorkGroupInvocations %u, "
        "maxImageDimension2D %u, bufferImageGranularity %llu",
        p->limits.maxMemoryAllocationCount, p->limits.maxStorageBufferRange, p->limits.maxComputeWorkGroupInvocations,
        p->limits.maxImageDimension2D, (unsigned long long)p->limits.bufferImageGranularity);
    if (p->apiVersion < VK_API_VERSION_1_2) {
        lack("Vulkan 1.2 on the device");
    }

    uint32_t ne = 0;
    vkEnumerateDeviceExtensionProperties(pd, NULL, &ne, NULL);
    VkExtensionProperties *ext = calloc(ne ? ne : 1, sizeof *ext);
    vkEnumerateDeviceExtensionProperties(pd, NULL, &ne, ext);
    fprintf(out, "extensions (%u):", ne);
    for (uint32_t i = 0; i < ne; i++) {
        fprintf(out, " %s", ext[i].extensionName);
    }
    fputc('\n', out);
    fflush(out);
    int host_ext = has_ext(ext, ne, VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME);
    if (!host_ext) {
        lack("VK_EXT_external_memory_host");
    } else {
        host_align = hostp.minImportedHostPointerAlignment;
        say("host pointer alignment: %llu", (unsigned long long)host_align);
    }
    static const char *recommended_ext[] = {
        "VK_KHR_maintenance5", "VK_EXT_device_generated_commands", "VK_KHR_external_memory_fd", "VK_EXT_external_memory_dma_buf",
        "VK_EXT_image_drm_format_modifier", "VK_EXT_queue_family_foreign", "VK_EXT_shader_stencil_export",
        "VK_EXT_graphics_pipeline_library", "VK_KHR_pipeline_binary", "VK_EXT_host_image_copy", "VK_EXT_custom_border_color",
        "VK_EXT_extended_dynamic_state", "VK_EXT_extended_dynamic_state2", "VK_KHR_dynamic_rendering", "VK_EXT_robustness2",
        "VK_EXT_shader_demote_to_helper_invocation", "VK_KHR_shader_float_controls", "VK_EXT_fragment_shader_interlock",
        "VK_EXT_rasterization_order_attachment_access",
    };
    for (size_t i = 0; i < sizeof recommended_ext / sizeof *recommended_ext; i++) {
        if (!has_ext(ext, ne, recommended_ext[i])) {
            say("recommended, absent: %s", recommended_ext[i]);
        }
    }

    VkPhysicalDeviceVulkan13Features f13 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    VkPhysicalDeviceVulkan12Features f12 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
                                            .pNext = p->apiVersion >= VK_API_VERSION_1_3 ? &f13 : NULL};
    VkPhysicalDeviceVulkan11Features f11 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, .pNext = &f12};
    VkPhysicalDeviceFeatures2 f2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &f11};
    vkGetPhysicalDeviceFeatures2(pd, &f2);
    VkPhysicalDeviceFeatures *f = &f2.features;
    /* what the guest's GPU work is built on (required) and what metal2vulkan uses where it is (logged) */
    struct { const char *name; VkBool32 v; int required; } feats[] = {
        {"sparseBinding", f->sparseBinding, 1},
        {"sparseResidencyBuffer", f->sparseResidencyBuffer, 1},
        {"shaderInt16", f->shaderInt16, 1},
        {"fragmentStoresAndAtomics", f->fragmentStoresAndAtomics, 1},
        {"vertexPipelineStoresAndAtomics", f->vertexPipelineStoresAndAtomics, 0},
        {"independentBlend", f->independentBlend, 1},
        {"imageCubeArray", f->imageCubeArray, 1},
        {"shaderStorageImageWriteWithoutFormat", f->shaderStorageImageWriteWithoutFormat, 1},
        {"shaderStorageImageReadWithoutFormat", f->shaderStorageImageReadWithoutFormat, 0},
        {"shaderInt64", f->shaderInt64, 0},
        {"shaderFloat64", f->shaderFloat64, 0},
        {"sparseResidencyAliased", f->sparseResidencyAliased, 0},
        {"sparseResidencyImage2D", f->sparseResidencyImage2D, 0},
        {"samplerAnisotropy", f->samplerAnisotropy, 0},
        {"depthClamp", f->depthClamp, 0},
        {"depthBiasClamp", f->depthBiasClamp, 0},
        {"sampleRateShading", f->sampleRateShading, 0},
        {"dualSrcBlend", f->dualSrcBlend, 0},
        {"logicOp", f->logicOp, 0},
        {"multiDrawIndirect", f->multiDrawIndirect, 0},
        {"drawIndirectFirstInstance", f->drawIndirectFirstInstance, 0},
        {"textureCompressionASTC_LDR", f->textureCompressionASTC_LDR, 0},
        {"textureCompressionETC2", f->textureCompressionETC2, 0},
        {"textureCompressionBC", f->textureCompressionBC, 0},
        {"shaderClipDistance", f->shaderClipDistance, 0},
        {"geometryShader", f->geometryShader, 0},
        {"tessellationShader", f->tessellationShader, 0},
        {"robustBufferAccess", f->robustBufferAccess, 0},
        {"storageBuffer16BitAccess", f11.storageBuffer16BitAccess, 1},
        {"uniformAndStorageBuffer16BitAccess", f11.uniformAndStorageBuffer16BitAccess, 0},
        {"multiview", f11.multiview, 0},
        {"samplerYcbcrConversion", f11.samplerYcbcrConversion, 0},
        {"shaderDrawParameters", f11.shaderDrawParameters, 0},
        {"shaderFloat16", f12.shaderFloat16, 1},
        {"shaderInt8", f12.shaderInt8, 1},
        /* not required: where it is missing metal2vulkan declares no u8[] view of a buffer and moves bytes
           through its word views (memory/leaf.rs Units::storage_on); Turnip has none on the Adreno 660 */
        {"storageBuffer8BitAccess", f12.storageBuffer8BitAccess, 0},
        {"timelineSemaphore", f12.timelineSemaphore, 1},
        {"bufferDeviceAddress", f12.bufferDeviceAddress, 1},
        {"descriptorIndexing", f12.descriptorIndexing, 1},
        {"runtimeDescriptorArray", f12.runtimeDescriptorArray, 1},
        {"descriptorBindingPartiallyBound", f12.descriptorBindingPartiallyBound, 1},
        {"descriptorBindingVariableDescriptorCount", f12.descriptorBindingVariableDescriptorCount, 0},
        {"descriptorBindingUpdateUnusedWhilePending", f12.descriptorBindingUpdateUnusedWhilePending, 0},
        {"shaderSampledImageArrayNonUniformIndexing", f12.shaderSampledImageArrayNonUniformIndexing, 0},
        {"shaderStorageBufferArrayNonUniformIndexing", f12.shaderStorageBufferArrayNonUniformIndexing, 0},
        {"scalarBlockLayout", f12.scalarBlockLayout, 1},
        {"uniformBufferStandardLayout", f12.uniformBufferStandardLayout, 0},
        {"vulkanMemoryModel", f12.vulkanMemoryModel, 0},
        {"shaderSubgroupExtendedTypes", f12.shaderSubgroupExtendedTypes, 0},
        {"separateDepthStencilLayouts", f12.separateDepthStencilLayouts, 0},
        {"hostQueryReset", f12.hostQueryReset, 0},
        {"imagelessFramebuffer", f12.imagelessFramebuffer, 0},
        {"samplerFilterMinmax", f12.samplerFilterMinmax, 0},
        {"drawIndirectCount", f12.drawIndirectCount, 0},
        {"shaderBufferInt64Atomics", f12.shaderBufferInt64Atomics, 0},
        {"synchronization2", f13.synchronization2, 0},
        {"dynamicRendering", f13.dynamicRendering, 0},
        {"maintenance4", f13.maintenance4, 0},
        {"subgroupSizeControl", f13.subgroupSizeControl, 0},
        {"computeFullSubgroups", f13.computeFullSubgroups, 0},
        {"shaderDemoteToHelperInvocation", f13.shaderDemoteToHelperInvocation, 0},
        {"robustImageAccess", f13.robustImageAccess, 0},
    };
    fprintf(out, "features:");
    for (size_t i = 0; i < sizeof feats / sizeof *feats; i++) {
        fprintf(out, " %s=%u", feats[i].name, feats[i].v);
    }
    fputc('\n', out);
    fflush(out);
    for (size_t i = 0; i < sizeof feats / sizeof *feats; i++) {
        if (!feats[i].v) {
            if (feats[i].required) {
                lack(feats[i].name);
            } else {
                say("recommended, absent: %s", feats[i].name);
            }
        }
    }

    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t t = 0; t < mp.memoryTypeCount; t++) {
        say("memory type %u: flags %#x heap %u (%llu MiB)", t, mp.memoryTypes[t].propertyFlags, mp.memoryTypes[t].heapIndex,
            (unsigned long long)(mp.memoryHeaps[mp.memoryTypes[t].heapIndex].size >> 20));
    }
    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, NULL);
    VkQueueFamilyProperties qf[16];
    if (nq > 16) {
        nq = 16;
    }
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf);
    for (uint32_t i = 0; i < nq; i++) {
        say("queue family %u: flags %#x, %u queues", i, qf[i].queueFlags, qf[i].queueCount);
        VkQueueFlags gc = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
        if (gfx_family == UINT32_MAX && (qf[i].queueFlags & gc) == gc) {
            gfx_family = i;
        }
        if (sparse_family == UINT32_MAX && (qf[i].queueFlags & VK_QUEUE_SPARSE_BINDING_BIT)) {
            sparse_family = i;
        }
    }
    if (gfx_family == UINT32_MAX) {
        lack("a graphics + compute queue");
    }
    if (sparse_family == UINT32_MAX) {
        lack("a queue that binds sparse memory");
    }
    if (gfx_family == UINT32_MAX) {
        return 1;
    }

    /* the device, with what the tests use */
    float prio = 1;
    VkDeviceQueueCreateInfo qci[2] = {
        {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = gfx_family, .queueCount = 1, .pQueuePriorities = &prio},
        {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = sparse_family, .queueCount = 1, .pQueuePriorities = &prio}};
    uint32_t nqci = sparse_family != UINT32_MAX && sparse_family != gfx_family ? 2 : 1;
    const char *want[2];
    uint32_t nwant = 0;
    if (host_ext) {
        want[nwant++] = VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME;
    }
    VkPhysicalDeviceFeatures en = {.sparseBinding = f->sparseBinding, .sparseResidencyBuffer = f->sparseResidencyBuffer};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = nqci, .pQueueCreateInfos = qci,
                              .enabledExtensionCount = nwant, .ppEnabledExtensionNames = want, .pEnabledFeatures = &en};
    r = vkCreateDevice(pd, &dci, NULL, &dev);
    say("device: vkCreateDevice VkResult %d", r);
    if (r != VK_SUCCESS) {
        lack("a device it can open (vkCreateDevice)");
        return 1;
    }
    DFN(vkGetDeviceQueue);
    DFN(vkCreateCommandPool);
    DFN(vkCreateFence);
    vkGetDeviceQueue(dev, gfx_family, 0, &gq);
    if (sparse_family != UINT32_MAX) {
        vkGetDeviceQueue(dev, sparse_family, 0, &sq);
    }
    VkCommandPoolCreateInfo cpi = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = gfx_family};
    VkFenceCreateInfo fci = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateCommandPool(dev, &cpi, NULL, &pool) != VK_SUCCESS || vkCreateFence(dev, &fci, NULL, &fence) != VK_SUCCESS) {
        lack("a command pool and a fence");
        return 1;
    }
    say("test: compute");
    if (!test_compute()) {
        lack("compute work that comes out right");
    }
    say("test: render");
    if (!test_render()) {
        lack("a render pass that comes out right");
    }
    if (host_ext) {
        say("test: host memory");
        if (!test_host()) {
            lack("GPU access to the phone's own memory (host pointer import)");
        }
        if (sparse_family != UINT32_MAX && f->sparseBinding) {
            say("test: sparse gather");
            if (!test_sparse_buffer()) {
                lack("sparse binds from host memory");
            }
            say("test: sparse image");
            if (!test_sparse_image()) {
                say("recommended, absent: sparse linear images from host memory (heap textures are copied instead)");
            }
        }
    }
    return failed;
}

JNIEXPORT jint JNICALL Java_com_asoft_ios_GpuCheck_probe(JNIEnv *env, jclass cls, jstring jloader, jstring jlog)
{
    (void)cls;
    const char *loader = (*env)->GetStringUTFChars(env, jloader, NULL);
    const char *log = (*env)->GetStringUTFChars(env, jlog, NULL);
    out = fopen(log, "a");
    if (!out) {
        return 3;
    }
    say("loader: %s", loader);
    int rc = check(loader);
    if (missing[0]) {
        say("missing: %s", missing);
    }
    say("result: %s", rc == 0 && !failed ? "pass" : "fail");
    fclose(out);
    (*env)->ReleaseStringUTFChars(env, jloader, loader);
    (*env)->ReleaseStringUTFChars(env, jlog, log);
    return rc == 0 && !failed ? 0 : 1;
}
