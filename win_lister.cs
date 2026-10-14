using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Collections.Generic;

public class WinLister {
    public delegate bool CallBack(IntPtr hwnd, int lParam);
    [DllImport("user32.dll")]
    public static extern int EnumWindows(CallBack x, int y);
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")]
    public static extern int GetClassName(IntPtr hWnd, StringBuilder text, int count);

    public static List<string> ListAll() {
        var list = new List<string>();
        EnumWindows((hwnd, l) => {
            uint pid;
            GetWindowThreadProcessId(hwnd, out pid);
            var sbTitle = new StringBuilder(256);
            GetWindowText(hwnd, sbTitle, 256);
            var sbClass = new StringBuilder(256);
            GetClassName(hwnd, sbClass, 256);
            if (sbTitle.Length > 0 || sbClass.ToString().Contains("tao") || sbClass.ToString().Contains("Chrome") || sbClass.ToString().Contains("Edge")) {
                list.Add(string.Format("PID {0} | HWND 0x{1:X} | Class: '{2}' | Title: '{3}'", pid, (long)hwnd, sbClass, sbTitle));
            }
            return true;
        }, 0);
        return list;
    }
}
