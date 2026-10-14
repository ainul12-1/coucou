using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Collections.Generic;

public class WinHelper2 {
    public delegate bool CallBack(IntPtr hwnd, int lParam);
    [DllImport("user32.dll")]
    public static extern int EnumWindows(CallBack x, int y);
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public static List<string> GetWindowsForProcess(uint targetPid) {
        var results = new List<string>();
        EnumWindows((hwnd, lParam) => {
            uint pid;
            GetWindowThreadProcessId(hwnd, out pid);
            if (pid == targetPid) {
                var sb = new StringBuilder(256);
                GetWindowText(hwnd, sb, 256);
                RECT rect;
                GetWindowRect(hwnd, out rect);
                bool vis = IsWindowVisible(hwnd);
                int w = rect.Right - rect.Left;
                int h = rect.Bottom - rect.Top;
                results.Add(string.Format("HWND: 0x{0:X} | Title: '{1}' | Vis: {2} | Pos: ({3}, {4}) | Size: {5}x{6}",
                    (long)hwnd, sb.ToString(), vis, rect.Left, rect.Top, w, h));
            }
            return true;
        }, 0);
        return results;
    }
}
