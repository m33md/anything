// Kol Novel Reader.exe: starts the reader with the Java in the runtime folder next to it,
// the same way "Kol Novel Reader.bat" does, but without a console window.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"
)

const mainClass = "com.kolnovel.reader.MainKt"

func main() {
	exe, err := os.Executable()
	if err != nil {
		fail("Could not find where this program is.\n\n" + err.Error())
	}
	dir := filepath.Dir(exe)

	if !isDir(filepath.Join(dir, "app")) {
		fail("The \"app\" folder is missing.\n\nRight-click the zip, choose \"Extract All...\", then run this program from the extracted folder.")
	}
	javaw := findJava(dir)
	if javaw == "" {
		fail("Java was not found: the \"runtime\" folder is missing.\n\nDownload the zip again and extract all of it.")
	}

	args := []string{
		"--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
		"--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
		"-Dfile.encoding=UTF-8",
	}
	// Same switch as "Kol Novel Reader (other graphics mode).bat".
	for _, a := range os.Args[1:] {
		if a == "--opengl" {
			args = append(args, "-Dskiko.renderApi=OPENGL")
		}
	}
	args = append(args, "-cp", filepath.Join(dir, "app", "*"), mainClass)

	cmd := exec.Command(javaw, args...)
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		fail("Could not start Java.\n\n" + err.Error())
	}

	// javaw shows nothing when it dies at once, so watch the first seconds and say so.
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			fail("The reader closed right after starting.\n\nRun \"Kol Novel Reader (if it does not open).bat\" to see the error, or try \"Kol Novel Reader (other graphics mode).bat\".")
		}
	case <-time.After(8 * time.Second):
	}
}

func findJava(dir string) string {
	roots := []string{
		filepath.Join(dir, "runtime"),
		// Older copies borrowed Java from Olympus Reader.
		filepath.Join(dir, "..", "OlympusReader", "runtime"),
	}
	if home := os.Getenv("USERPROFILE"); home != "" {
		roots = append(roots, filepath.Join(home, "Desktop", "OlympusReader", "runtime"))
	}
	if od := os.Getenv("OneDrive"); od != "" {
		roots = append(roots, filepath.Join(od, "Desktop", "OlympusReader", "runtime"))
	}
	for _, r := range roots {
		if p := filepath.Join(r, "bin", "javaw.exe"); isFile(p) {
			return p
		}
	}
	if p, err := exec.LookPath("javaw"); err == nil {
		return p
	}
	return ""
}

func isDir(p string) bool  { st, err := os.Stat(p); return err == nil && st.IsDir() }
func isFile(p string) bool { st, err := os.Stat(p); return err == nil && !st.IsDir() }

func fail(text string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	box := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString("Kol Novel Reader")
	const mbIconError = 0x10
	box.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), mbIconError)
	os.Exit(1)
}
