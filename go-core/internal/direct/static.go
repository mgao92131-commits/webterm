package direct

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

func resolveStaticRoot(configured string) string {
	if configured != "" {
		return configured
	}
	wd, err := os.Getwd()
	if err != nil {
		return ""
	}
	for dir := wd; ; dir = filepath.Dir(dir) {
		candidate := filepath.Join(dir, "web")
		if fileExists(filepath.Join(candidate, "index.html")) {
			return candidate
		}
		next := filepath.Dir(dir)
		if next == dir {
			break
		}
	}
	return ""
}

func serveStatic(w http.ResponseWriter, r *http.Request, root string) {
	if root == "" {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	root, err := filepath.Abs(root)
	if err != nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	cleanPath := path.Clean("/" + r.URL.Path)
	if cleanPath == "/" {
		cleanPath = "/index.html"
	}
	target := filepath.Join(root, filepath.FromSlash(cleanPath))
	if !insideRoot(root, target) {
		writeError(w, http.StatusForbidden, "forbidden")
		return
	}
	if !fileExists(target) {
		target = filepath.Join(root, "index.html")
	}
	if !fileExists(target) {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, target)
}

func insideRoot(root string, target string) bool {
	rel, err := filepath.Rel(root, target)
	if err != nil {
		return false
	}
	return rel == "." || (!strings.HasPrefix(rel, ".."+string(filepath.Separator)) && rel != "..")
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
