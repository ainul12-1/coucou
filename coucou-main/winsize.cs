using System;
using System.Runtime.InteropServices;
using System.Text;

public class WinSizeCheck {
    public delegate bool CallBack(IntPtr hwnd, int lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(CallBack cb, IntPtr lp);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder sb, int max);
    [DllImport("user32.dll")] public static extern int GetClassName(IntPtr hWnd, StringBuilder sb, int max);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }

    public static void Run(uint targetPid) {
        EnumWindows((h, p) => {
            uint pid;
            GetWindowThreadProcessId(h, out pid);
            if (pid == targetPid) {
                var sb1 = new StringBuilder(256); GetWindowText(h, sb1, 256);
                var sb2 = new StringBuilder(256); GetClassName(h, sb2, 256);
                RECT r; GetWindowRect(h, out r);
                Console.WriteLine("HWND: 0x{0:X} | Class: {1} | Title: {2} | Rect: ({3},{4}) {5}x{6}",
                    (long)h, sb2, sb1, r.L, r.T, r.R - r.L, r.B - r.T);
            }
            return true;
        }, IntPtr.Zero);
    }
}
