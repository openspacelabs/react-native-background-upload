package ai.openspace.backgroundupload

import android.system.ErrnoException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class StorageCauseTest {
  private val defaultErrnoNameOf = StorageCause.errnoNameOf

  @After
  fun tearDown() {
    StorageCause.errnoNameOf = defaultErrnoNameOf
  }

  @Test
  fun `suffix names the errno when the cause chain holds an ErrnoException`() {
    // On the JVM the android.jar stub leaves errno at 0 and cannot name it, so
    // the test names it. On a device the default reads errno and OsConstants.
    StorageCause.errnoNameOf = { if (it is ErrnoException) "EACCES" else null }
    val e = IOException("could not create /q", ErrnoException("mkdir", 13))
    assertEquals(" (cause: java.io.IOException; errno EACCES)", StorageCause.suffix(e, withMessage = false))
    val wrapped = QueueException(QueueException.E_STORAGE, "could not save the entry", e)
    assertEquals(" (cause: ai.openspace.backgroundupload.QueueException; errno EACCES)",
      StorageCause.suffix(wrapped, withMessage = false))
  }

  @Test
  fun `the default errno reader ignores a throwable that is not an ErrnoException`() {
    assertEquals(null, StorageCause.errnoNameOf(IOException("x")))
  }
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
