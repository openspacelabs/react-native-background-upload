package ai.openspace.backgroundupload

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class UploaderReactPackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == UploaderModule.NAME) UploaderModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      UploaderModule.NAME to ReactModuleInfo(
        name = UploaderModule.NAME,
        className = UploaderModule.NAME,
        canOverrideExistingModule = false,
        // Created on first JS access. Uploads outlive the module (WorkManager
        // runs them, the journal records their outcomes), so nothing is lost by
        // not constructing it at startup.
        needsEagerInit = false,
        isCxxModule = false,
        isTurboModule = true,
      ),
    )
  }
}
