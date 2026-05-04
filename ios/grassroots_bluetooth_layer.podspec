#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
#
Pod::Spec.new do |s|
  s.name             = 'grassroots_bluetooth_layer'
  s.version          = '0.1.0'
  s.summary          = 'Unified bondless BLE central and peripheral transport plugin.'
  s.description      = <<-DESC
Unified bondless BLE central and peripheral transport plugin for Grassroots-style peer discovery.
                       DESC
  s.homepage         = 'https://github.com/permissionlesstech/grassroots_networking'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Permissionless Tech' => 'dev@permissionless.tech' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386'
  }
  s.swift_version = '5.0'
end
