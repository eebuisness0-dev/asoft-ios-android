package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The framebuffer-fetch reason of the GPU check, fed with lines of the system-driver logs of real phones (app 0.1.4/0.1.5). */
public class GpuCheckFetchTest {
    /** Xclipse 940 (Exynos 2400, issue 22): every test passed, both fetch extensions absent. */
    private static final List<String> XCLIPSE_940 = Arrays.asList(
            "gpu: Samsung Xclipse 940", "device api: Vulkan 1.3.304", "device: vkCreateDevice VkResult 0",
            "recommended, absent: VK_KHR_maintenance5", "recommended, absent: VK_EXT_device_generated_commands",
            "recommended, absent: VK_EXT_fragment_shader_interlock",
            "recommended, absent: VK_EXT_rasterization_order_attachment_access",
            "recommended, absent: shaderFloat64", "compute: 0 of 4096 words wrong", "render: 0 of 4096 pixels wrong",
            "result: pass");

    @Test
    public void xclipseWithNeitherExtensionGetsTheReason() {
        assertEquals(GpuCheck.FRAMEBUFFER_FETCH, GpuCheck.framebufferFetchLack(XCLIPSE_940));
    }

    @Test
    public void oneOfTheTwoExtensionsIsEnough() {
        // a driver that lists only one of them as absent has the other: no reason
        java.util.ArrayList<String> hasRaster = new java.util.ArrayList<>(XCLIPSE_940);
        hasRaster.remove("recommended, absent: VK_EXT_rasterization_order_attachment_access");
        assertNull(GpuCheck.framebufferFetchLack(hasRaster));
        java.util.ArrayList<String> hasInterlock = new java.util.ArrayList<>(XCLIPSE_940);
        hasInterlock.remove("recommended, absent: VK_EXT_fragment_shader_interlock");
        assertNull(GpuCheck.framebufferFetchLack(hasInterlock));
    }

    @Test
    public void aLogWithoutTheLinesGivesNoReason() {
        // no "recommended" lines (the probe did not get that far): nothing is claimed
        assertNull(GpuCheck.framebufferFetchLack(Arrays.asList("gpu: x", "result: fail")));
        assertNull(GpuCheck.framebufferFetchLack(Arrays.<String>asList()));
        assertNotNull(GpuCheck.FRAMEBUFFER_FETCH);
    }

    @Test
    public void anEngineWithTheUnorderedFetchDoesNotRuleOutADriverWithNeitherExtension() {
        GpuCheck.unorderedFetchEngine = true;
        try {
            assertNull(GpuCheck.framebufferFetchLack(XCLIPSE_940));
        } finally {
            GpuCheck.unorderedFetchEngine = false;
        }
    }
}
