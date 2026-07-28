require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'CapacitorStarPrinter'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  # Repo tags are v-prefixed (v0.3.0), so the tag is NOT just s.version.
  s.source = { :git => package['repository']['url'], :tag => "v#{s.version}" }
  s.source_files = 'ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target = '15.0'
  s.dependency 'Capacitor'
  s.dependency 'StarIO10', '~> 2.12'
  s.swift_version = '5.9'
end
