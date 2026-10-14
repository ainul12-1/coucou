using System;
using System.Runtime.InteropServices;

public class FindCoucou {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public static string Check() {
        IntPtr hwnd = FindWindow(null, "Coucou");
        if (hwnd == IntPtr.Zero) return "Not found";
        RECT r;
        GetWindowRect(hwnd, out r);
        bool vis = IsWindowVisible(hwnd);
        return string.Format("Found HWND 0x{0:X}: Vis={1}, Rect=({2},{3})-({4},{5}), Size={6}x{7}",
            (long)hwnd, vis, r.Left, r.Top, r.Right, r.Bottom, r.Right - r.Left, r.Bottom - r.Top);
    }
}
