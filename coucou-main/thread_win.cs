using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Collections.Generic;
using System.Diagnostics;

public class ThreadWinHelper {
    public delegate bool EnumThreadDelegate(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern bool EnumThreadWindows(int dwThreadId, EnumThreadDelegate lpfn, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")]
    public static extern int GetClassName(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern int GetWindowLong(IntPtr hWnd, int nIndex);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public static List<string> FindWindows(int pid) {
        var list = new List<string>();
        Process p = Process.GetProcessById(pid);
        foreach (ProcessThread t in p.Threads) {
            EnumThreadWindows(t.Id, (hwnd, lparam) => {
                var sbTitle = new StringBuilder(256);
                GetWindowText(hwnd, sbTitle, 256);
                var sbClass = new StringBuilder(256);
                GetClassName(hwnd, sbClass, 256);
                RECT r;
                GetWindowRect(hwnd, out r);
                bool vis = IsWindowVisible(hwnd);
                int exStyle = GetWindowLong(hwnd, -20); // GWL_EXSTYLE
                list.Add(string.Format("HWND: 0x{0:X} | Class: {1} | Title: '{2}' | Vis: {3} | Rect: ({4},{5})-({6},{7}) | ExStyle: 0x{8:X}",
                    (long)hwnd, sbClass, sbTitle, vis, r.Left, r.Top, r.Right, r.Bottom, exStyle));
                return true;
            }, IntPtr.Zero);
        }
        return list;
    }
}
