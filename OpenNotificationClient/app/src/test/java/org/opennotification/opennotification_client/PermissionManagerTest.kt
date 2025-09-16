package org.opennotification.opennotification_client

import org.junit.Test
import org.junit.Assert.*
import org.opennotification.opennotification_client.utils.PermissionManager

/**
 * Unit test to verify PermissionManager methods have correct signatures
 * and data classes work as expected.
 */
class PermissionManagerTest {

    @Test
    fun permissionSummary_allPermissionsGranted_returnsTrue() {
        val summary = PermissionManager.PermissionSummary(
            notificationPermissionGranted = true,
            batteryOptimizationIgnored = true,
            fullScreenIntentPermissionGranted = true,
            overlayPermissionGranted = true
        )
        
        assertTrue("All permissions should be granted", summary.allPermissionsGranted)
    }

    @Test
    fun permissionSummary_missingNotificationPermission_returnsFalse() {
        val summary = PermissionManager.PermissionSummary(
            notificationPermissionGranted = false,
            batteryOptimizationIgnored = true,
            fullScreenIntentPermissionGranted = true,
            overlayPermissionGranted = true
        )
        
        assertFalse("Should return false when notification permission is missing", summary.allPermissionsGranted)
    }

    @Test
    fun permissionSummary_missingBatteryOptimization_returnsFalse() {
        val summary = PermissionManager.PermissionSummary(
            notificationPermissionGranted = true,
            batteryOptimizationIgnored = false,
            fullScreenIntentPermissionGranted = true,
            overlayPermissionGranted = true
        )
        
        assertFalse("Should return false when battery optimization is not ignored", summary.allPermissionsGranted)
    }

    @Test
    fun permissionSummary_missingOverlayPermission_returnsFalse() {
        val summary = PermissionManager.PermissionSummary(
            notificationPermissionGranted = true,
            batteryOptimizationIgnored = true,
            fullScreenIntentPermissionGranted = true,
            overlayPermissionGranted = false
        )
        
        assertFalse("Should return false when overlay permission is missing", summary.allPermissionsGranted)
    }

    @Test
    fun permissionSummary_defaultValues_workCorrectly() {
        val summary = PermissionManager.PermissionSummary(
            notificationPermissionGranted = true,
            batteryOptimizationIgnored = true
            // fullScreenIntentPermissionGranted and overlayPermissionGranted use default true
        )
        
        assertTrue("Should work with default parameter values", summary.allPermissionsGranted)
    }
}