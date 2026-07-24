require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name = package["name"]
  s.version = package["version"]
  s.license = { type: "MIT" }
  s.homepage = "https://github.com/openspacelabs/react-native-background-upload"
  s.authors = package["author"]
  s.summary = package["description"]
  s.source = {
    git: "https://github.com/openspacelabs/react-native-background-upload.git",
    tag: "v#{s.version}"
  }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  # RNFileUploader.h imports the codegen spec header, which is Obj-C++ only. Keep
  # every header out of the public umbrella so a consumer's plain Obj-C
  # `@import react_native_background_upload;` still compiles — that import is how
  # the AppDelegate reaches RNBackgroundUpload's background-session handler.
  s.private_header_files = "ios/**/*.h"

  s.platform = :ios, "15.1"
  s.swift_version = "5.0"
  s.pod_target_xcconfig = { "DEFINES_MODULE" => "YES" }

  # Pulls in React-Core plus the New Architecture / TurboModule dependencies.
  install_modules_dependencies(s)
end
