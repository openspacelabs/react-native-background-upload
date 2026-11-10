package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class StorageCauseTest {
  @Test
  fun `suffix names the class and the message`() {
    val e = FileNotFoundException("/q/settings.json.tmp: open failed: ENOSPC (No space left on device)")
    assertEquals(
      " (cause: java.io.FileNotFoundException: /q/settings.json.tmp: open failed: ENOSPC (No space left on device))",
      StorageCause.suffix(e),
    )
    assertEquals(" (cause: java.io.FileNotFoundException)", StorageCause.suffix(e, withMessage = false))
  }

  @Test
  fun `reject message keeps the old text first`() {
    assertEquals("disk (cause: java.io.IOException)", StorageCause.rejectMessage(IOException("disk")))
    assertEquals(
      "IllegalStateException (cause: java.lang.IllegalStateException)",
      StorageCause.rejectMessage(IllegalStateException()),
    )
  }
}
