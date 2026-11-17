package ai.openspace.backgroundupload

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stop-reason mapping. Each row pairs the WorkManager constant with the
 * value the Android reference documents for it, so a changed constant fails
 * here and not on a device.
 */
class StopKindTest {
  private data class Row(val name: String, val constant: Int, val documented: Int, val kind: StopKind)

  private val table = listOf(
    Row("TIMEOUT", WorkInfo.STOP_REASON_TIMEOUT, 3, StopKind.TIME_LIMIT),
    Row("FOREGROUND_SERVICE_TIMEOUT", WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT, -128, StopKind.TIME_LIMIT),
    Row("CONSTRAINT_CONNECTIVITY", WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, 7, StopKind.NETWORK),
    Row("QUOTA", WorkInfo.STOP_REASON_QUOTA, 10, StopKind.QUOTA),
    Row("APP_STANDBY", WorkInfo.STOP_REASON_APP_STANDBY, 12, StopKind.APP_STANDBY),
    Row("BACKGROUND_RESTRICTION", WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, 11, StopKind.BACKGROUND_RESTRICTION),
    Row("DEVICE_STATE", WorkInfo.STOP_REASON_DEVICE_STATE, 4, StopKind.DEVICE_STATE),
    Row("USER", WorkInfo.STOP_REASON_USER, 13, StopKind.USER),
    Row("CANCELLED_BY_APP", WorkInfo.STOP_REASON_CANCELLED_BY_APP, 1, StopKind.OTHER),
    Row("PREEMPT", WorkInfo.STOP_REASON_PREEMPT, 2, StopKind.OTHER),
    Row("CONSTRAINT_BATTERY_NOT_LOW", WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW, 5, StopKind.OTHER),
    Row("CONSTRAINT_CHARGING", WorkInfo.STOP_REASON_CONSTRAINT_CHARGING, 6, StopKind.OTHER),
    Row("CONSTRAINT_DEVICE_IDLE", WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE, 8, StopKind.OTHER),
    Row("CONSTRAINT_STORAGE_NOT_LOW", WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW, 9, StopKind.OTHER),
    Row("SYSTEM_PROCESSING", WorkInfo.STOP_REASON_SYSTEM_PROCESSING, 14, StopKind.OTHER),
    Row("ESTIMATED_APP_LAUNCH_TIME_CHANGED", WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED, 15, StopKind.OTHER),
    Row("NOT_STOPPED", WorkInfo.STOP_REASON_NOT_STOPPED, -256, StopKind.OTHER),
    Row("UNKNOWN", WorkInfo.STOP_REASON_UNKNOWN, -512, StopKind.OTHER),
  )

  @Test
  fun `each WorkManager constant has its documented value`() {
    for (row in table) assertEquals(row.name, row.documented, row.constant)
  }

  @Test
  fun `each stop reason maps to its kind`() {
    for (row in table) assertEquals(row.name, row.kind, StopKind.of(row.constant))
  }

  @Test
  fun `a reason a later Android adds is OTHER`() {
    assertEquals(StopKind.OTHER, StopKind.of(99))
  }

  @Test
  fun `only quota, standby, background restriction, device state and user are logged`() {
    assertEquals(
      setOf(StopKind.QUOTA, StopKind.APP_STANDBY, StopKind.BACKGROUND_RESTRICTION, StopKind.DEVICE_STATE, StopKind.USER),
      StopKind.values().filter { it.logged }.toSet(),
    )
  }
}
