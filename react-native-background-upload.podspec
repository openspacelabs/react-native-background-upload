require "json"

 json = File.read(File.join(__dir__, "package.json"))
 package = JSON.parse(json).deep_symbolize_keys

 Pod::Spec.new do |s|
   s.name = package[:name]
   s.version = package[:version]
   s.license = { type: "MIT" }
   s.homepage = "https://github.com/openspacelabs/react-native-background-upload"
   s.authors = package[:author]
   s.summary = package[:description]
   s.source = { git: package[:repository][:url] }
   s.source_files = "ios/**/*.{h,m,swift}"
   s.platform = :ios, "15.1"
   s.swift_version = "5.0"
   s.pod_target_xcconfig = { "DEFINES_MODULE" => "YES" }

   s.dependency "React-Core"
 end
