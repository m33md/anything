# Gives the setup .exe jpackage made Convx's icon (it keeps Java's otherwise) and puts the branded
# MSI inside it: jpackage builds the .exe from a second MSI of its own, made before brand-msi.ps1
# ran, so without this the .exe would install with the plain WiX pictures. The .exe is jpackage's
# small launcher with the MSI kept as a resource named "msi"; both changes are resource edits.
# Anything unexpected leaves the original .exe untouched.
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $Msi,
    [Parameter(Mandatory)] [string] $Icon
)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;

public static class ConvxSetupExe {
    const uint AsDataFile = 0x2;
    static readonly IntPtr RtIcon = (IntPtr)3, RtRcData = (IntPtr)10, RtGroupIcon = (IntPtr)14;

    delegate bool NameProc(IntPtr module, IntPtr type, IntPtr name, IntPtr param);
    delegate bool LangProc(IntPtr module, IntPtr type, IntPtr name, ushort lang, IntPtr param);

    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr LoadLibraryExW(string path, IntPtr file, uint flags);
    [DllImport("kernel32", SetLastError = true)] static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool EnumResourceNamesW(IntPtr module, IntPtr type, NameProc proc, IntPtr param);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool EnumResourceLanguagesW(IntPtr module, IntPtr type, IntPtr name, LangProc proc, IntPtr param);
    [DllImport("kernel32", SetLastError = true)] static extern IntPtr FindResourceExW(IntPtr module, IntPtr type, IntPtr name, ushort lang);
    [DllImport("kernel32", SetLastError = true)] static extern IntPtr LoadResource(IntPtr module, IntPtr info);
    [DllImport("kernel32", SetLastError = true)] static extern IntPtr LockResource(IntPtr data);
    [DllImport("kernel32", SetLastError = true)] static extern uint SizeofResource(IntPtr module, IntPtr info);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr BeginUpdateResourceW(string path, bool deleteExisting);
    [DllImport("kernel32", SetLastError = true)] static extern bool UpdateResourceW(IntPtr update, IntPtr type, IntPtr name, ushort lang, byte[] data, uint size);
    [DllImport("kernel32", SetLastError = true)] static extern bool EndUpdateResourceW(IntPtr update, bool discard);

    static Exception Fail(string what) { return new InvalidOperationException(what + " (Windows error " + Marshal.GetLastWin32Error() + ")"); }

    // A resource name is a number or a string; strings go to Win32 as a pointer that must be freed.
    sealed class Name : IDisposable {
        public readonly int Id; public readonly string Text; public readonly IntPtr Ptr;
        public Name(int id) { Id = id; Ptr = (IntPtr)id; }
        public Name(string text) { Text = text; Ptr = Marshal.StringToHGlobalUni(text); }
        public static Name From(IntPtr raw) {
            return ((long)raw >> 16) == 0 ? new Name((int)(long)raw) : new Name(Marshal.PtrToStringUni(raw));
        }
        public void Dispose() { if (Text != null) Marshal.FreeHGlobal(Ptr); }
        public override string ToString() { return Text ?? ("#" + Id); }
    }

    static List<Name> Names(IntPtr module, IntPtr type) {
        var names = new List<Name>();
        EnumResourceNamesW(module, type, (m, t, n, p) => { names.Add(Name.From(n)); return true; }, IntPtr.Zero);
        return names;
    }

    static List<ushort> Langs(IntPtr module, IntPtr type, Name name) {
        var langs = new List<ushort>();
        EnumResourceLanguagesW(module, type, name.Ptr, (m, t, n, l, p) => { langs.Add(l); return true; }, IntPtr.Zero);
        return langs;
    }

    static byte[] Read(IntPtr module, IntPtr type, Name name, ushort lang) {
        IntPtr info = FindResourceExW(module, type, name.Ptr, lang);
        if (info == IntPtr.Zero) return null;
        uint size = SizeofResource(module, info);
        var bytes = new byte[size];
        Marshal.Copy(LockResource(LoadResource(module, info)), bytes, 0, (int)size);
        return bytes;
    }

    static IntPtr Open(string exe) {
        IntPtr module = LoadLibraryExW(exe, IntPtr.Zero, AsDataFile);
        if (module == IntPtr.Zero) throw Fail("Couldn't open " + exe);
        return module;
    }

    public static string Brand(string exe, string msi, string ico) {
        byte[] msiBytes = File.ReadAllBytes(msi);
        byte[] icoBytes = File.ReadAllBytes(ico);
        using (var msiName = new Name("msi")) {
            // What's there now: the MSI's languages, the icon group Explorer shows (the first one)
            // and the icon pictures it points at.
            List<ushort> msiLangs;
            Name group; List<ushort> groupLangs;
            var oldIcons = new List<KeyValuePair<int, ushort>>();
            int topIcon = 0;
            IntPtr module = Open(exe);
            try {
                msiLangs = Langs(module, RtRcData, msiName);
                if (msiLangs.Count == 0) throw new InvalidOperationException("The .exe has no MSI inside; jpackage's layout changed");
                var groups = Names(module, RtGroupIcon);
                group = groups.Count > 0 ? groups[0] : new Name(1);
                groupLangs = groups.Count > 0 ? Langs(module, RtGroupIcon, group) : new List<ushort>();
                if (groupLangs.Count == 0) groupLangs.Add(msiLangs[0]);
                foreach (var lang in groupLangs) {
                    byte[] dir = groups.Count > 0 ? Read(module, RtGroupIcon, group, lang) : null;
                    int count = dir == null || dir.Length < 6 ? 0 : BitConverter.ToUInt16(dir, 4);
                    for (int i = 0; i < count && 6 + i * 14 + 14 <= dir.Length; i++) {
                        int id = BitConverter.ToUInt16(dir, 6 + i * 14 + 12);
                        using (var icon = new Name(id))
                            foreach (var iconLang in Langs(module, RtIcon, icon)) oldIcons.Add(new KeyValuePair<int, ushort>(id, iconLang));
                    }
                }
                foreach (var icon in Names(module, RtIcon)) if (icon.Text == null) topIcon = Math.Max(topIcon, icon.Id);
            } finally {
                FreeLibrary(module);
            }

            // The .ico: a 6-byte header, then 16 bytes per picture pointing at its data.
            if (icoBytes.Length < 6 || BitConverter.ToUInt16(icoBytes, 2) != 1) throw new InvalidDataException(ico + " isn't an .ico file");
            int pictures = BitConverter.ToUInt16(icoBytes, 4);
            var groupDir = new MemoryStream();
            var w = new BinaryWriter(groupDir);
            w.Write((ushort)0); w.Write((ushort)1); w.Write((ushort)pictures);
            var data = new List<byte[]>();
            for (int i = 0; i < pictures; i++) {
                int at = 6 + i * 16;
                int size = BitConverter.ToInt32(icoBytes, at + 8), offset = BitConverter.ToInt32(icoBytes, at + 12);
                var picture = new byte[size];
                Array.Copy(icoBytes, offset, picture, 0, size);
                data.Add(picture);
                w.Write(icoBytes, at, 12);                 // width, height, colours, reserved, planes, bit count, size
                w.Write((ushort)(topIcon + 1 + i));        // the RT_ICON id, where the .ico had a file offset
            }
            byte[] groupBytes = groupDir.ToArray();

            IntPtr update = BeginUpdateResourceW(exe, false);
            if (update == IntPtr.Zero) throw Fail("Couldn't edit " + exe);
            bool done = false;
            try {
                foreach (var lang in msiLangs)
                    if (!UpdateResourceW(update, RtRcData, msiName.Ptr, lang, msiBytes, (uint)msiBytes.Length)) throw Fail("Couldn't replace the MSI");
                foreach (var old in oldIcons)
                    if (!UpdateResourceW(update, RtIcon, (IntPtr)old.Key, old.Value, null, 0)) throw Fail("Couldn't remove Java's icon");
                foreach (var lang in groupLangs) {
                    for (int i = 0; i < pictures; i++)
                        if (!UpdateResourceW(update, RtIcon, (IntPtr)(topIcon + 1 + i), lang, data[i], (uint)data[i].Length)) throw Fail("Couldn't add the icon");
                    if (!UpdateResourceW(update, RtGroupIcon, group.Ptr, lang, groupBytes, (uint)groupBytes.Length)) throw Fail("Couldn't set the icon");
                }
                if (!EndUpdateResourceW(update, false)) throw Fail("Couldn't save " + exe);
                done = true;
            } finally {
                if (!done) EndUpdateResourceW(update, true);
            }

            // Read it back, so a broken .exe shows here rather than on someone's PC.
            module = Open(exe);
            try {
                foreach (var lang in msiLangs) {
                    byte[] inside = Read(module, RtRcData, msiName, lang);
                    if (inside == null || inside.Length != msiBytes.Length) throw new InvalidOperationException("The MSI inside the .exe didn't come back whole");
                }
                byte[] check = Read(module, RtGroupIcon, group, groupLangs[0]);
                if (check == null || BitConverter.ToUInt16(check, 4) != pictures) throw new InvalidOperationException("The icon didn't come back");
            } finally {
                FreeLibrary(module);
            }
            return string.Format("MSI ({0} bytes, languages {1}) and icon group {2} ({3} pictures, replacing {4}) set",
                msiBytes.Length, string.Join(",", msiLangs), group, pictures, oldIcons.Count);
        }
    }
}
'@

$work = "$Exe.branding"
try {
    Copy-Item -LiteralPath $Exe -Destination $work -Force
    $result = [ConvxSetupExe]::Brand((Resolve-Path $work).Path, (Resolve-Path $Msi).Path, (Resolve-Path $Icon).Path)
    Write-Host $result
    Move-Item -LiteralPath $work -Destination $Exe -Force
    Write-Host "Branded $Exe"
} catch {
    Write-Host "::warning::Couldn't brand the setup .exe ($($_.Exception.Message)); shipping it as jpackage made it"
    Remove-Item -LiteralPath $work -Force -ErrorAction SilentlyContinue
}
