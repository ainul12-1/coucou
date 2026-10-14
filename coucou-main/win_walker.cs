using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Collections.Generic;

public class WinWalker {
    [DllImport("user32.dll")]
    public static extern IntPtr GetDesktopWindow();
    [DllImport("user32.dll")]
    public static extern IntPtr GetWindow(IntPtr hWnd, uint uCmd);
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")]
    public static extern int GetClassName(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public static List<string> Walk(uint targetPid) {
        var list = new List<string>();
        IntPtr cur = GetWindow(GetDesktopWindow(), 5); // GW_CHILD = 5
        while (cur != IntPtr.Zero) {
            uint pid;
            GetWindowThreadProcessId(cur, out pid);
            if (pid == targetPid) {
                var sbTitle = new StringBuilder(256);
                GetWindowText(cur, sbTitle, 256);
                var sbClass = new StringBuilder(256);
                GetClassName(cur, sbClass, 256);
                RECT r;
                GetWindowRect(cur, out r);
                bool vis = IsWindowVisible(cur);
                list.Add(string.Format("HWND: 0x{0:X} | Class: '{1}' | Title: '{2}' | Vis: {3} | Rect: ({4},{5})-({6},{7})",
                    (long)cur, sbClass, sbTitle, vis, r.Left, r.Top, r.Right, r.Bottom));
            }
            cur = GetWindow(cur, 2); // GW_HWNDNEXT = 2
        }
        return list;
    }
}
