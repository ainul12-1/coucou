Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "C:\Users\ainul\Documents\coucou\coucou-main\windows\bridge"
WshShell.Run "node server.js", 0, False
