import AppKit

// Classic no-storyboard AppKit entry point. `LSUIElement` in Info.plist already keeps
// this app out of the Dock and without a main menu bar, but we set the activation
// policy explicitly too so behavior doesn't depend on Info.plist processing order.
let appDelegate = AppDelegate()
let application = NSApplication.shared
application.delegate = appDelegate
application.setActivationPolicy(.accessory)
application.run()
