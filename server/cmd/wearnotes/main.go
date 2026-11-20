package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
	_ "modernc.org/sqlite"
)

type config struct {
	addr         string
	data         string
	oidcIssuer   string
	oidcClientID string
	oidcSecret   string
	oidcRedirect string
}

type note struct {
	ID          string           `json:"id"`
	Title       string           `json:"title"`
	Body        string           `json:"body"`
	Color       string           `json:"color"`
	Pinned      bool             `json:"pinned"`
	Archived    bool             `json:"archived"`
	Trashed     bool             `json:"trashed"`
	Version     int64            `json:"version"`
	CreatedAt   int64            `json:"createdAt"`
	UpdatedAt   int64            `json:"updatedAt"`
	ReminderAt  *int64           `json:"reminderAt,omitempty"`
	Labels      []label          `json:"labels,omitempty"`
	PhotoCount  int              `json:"photoCount"`
	Attachments []attachmentMeta `json:"attachments,omitempty"`
}

type label struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Color string `json:"color"`
}

type attachmentMeta struct {
	ID       string `json:"id"`
	Filename string `json:"filename"`
	Mime     string `json:"mime"`
	Size     int64  `json:"size"`
	URL      string `json:"url"`
}

type noteRequest struct {
	Title      string `json:"title"`
	Body       string `json:"body"`
	Color      string `json:"color"`
	Pinned     *bool  `json:"pinned"`
	Archived   *bool  `json:"archived"`
	ReminderAt *int64 `json:"reminderAt"`
	IfVersion  *int64 `json:"ifVersion"`
}

type appendRequest struct {
	Text      string `json:"text"`
	IfVersion *int64 `json:"ifVersion"`
}

type api struct {
	db           *sql.DB
	data         string
	oidcIssuer   string
	oidcProvider *oidc.Provider
	oidcConfig   *oauth2.Config
}

func main() {
	cfg := config{
		addr:         env("WEARNOTES_ADDR", ":8080"),
		data:         env("WEARNOTES_DATA", "./data"),
		oidcIssuer:   os.Getenv("WEARNOTES_OIDC_ISSUER"),
		oidcClientID: os.Getenv("WEARNOTES_OIDC_CLIENT_ID"),
		oidcSecret:   os.Getenv("WEARNOTES_OIDC_CLIENT_SECRET"),
		oidcRedirect: os.Getenv("WEARNOTES_OIDC_REDIRECT_URL"),
	}
	if err := os.MkdirAll(filepath.Join(cfg.data, "attachments"), 0o755); err != nil {
		log.Fatal(err)
	}
	db, err := sql.Open("sqlite", filepath.Join(cfg.data, "wearnotes.db")+"?_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)")
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	if err := migrate(db); err != nil {
		log.Fatal(err)
	}

	service := &api{db: db, data: cfg.data, oidcIssuer: strings.TrimRight(cfg.oidcIssuer, "/")}
	if cfg.oidcIssuer != "" && cfg.oidcClientID != "" {
		provider, err := oidc.NewProvider(context.Background(), strings.TrimRight(cfg.oidcIssuer, "/"))
		if err != nil {
			log.Fatalf("initialize OIDC provider: %v", err)
		}
		service.oidcProvider = provider
		service.oidcConfig = &oauth2.Config{
			ClientID:     cfg.oidcClientID,
			ClientSecret: cfg.oidcSecret,
			Endpoint:     provider.Endpoint(),
			RedirectURL:  cfg.oidcRedirect,
			Scopes:       []string{oidc.ScopeOpenID, "profile", "email"},
		}
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", service.health)
	mux.HandleFunc("POST /v1/auth/devices", service.createDevice)
	mux.HandleFunc("GET /v1/auth/oidc/config", service.oidcConfigEndpoint)
	mux.HandleFunc("POST /v1/auth/oidc/device", service.oidcDevice)
	mux.HandleFunc("GET /v1/sync", service.sync)
	mux.HandleFunc("GET /v1/notes", service.listNotes)
	mux.HandleFunc("GET /v1/search", service.searchNotes)
	mux.HandleFunc("POST /v1/notes", service.createNote)
	mux.HandleFunc("GET /v1/notes/{id}", service.getNote)
	mux.HandleFunc("PATCH /v1/notes/{id}", service.updateNote)
	mux.HandleFunc("DELETE /v1/notes/{id}", service.deleteNote)
	mux.HandleFunc("POST /v1/notes/{id}/append", service.appendNote)
	mux.HandleFunc("POST /v1/notes/{id}/attachments", service.createAttachment)
	mux.HandleFunc("GET /v1/attachments/{id}", service.getAttachment)
	mux.HandleFunc("POST /v1/notes/{id}/archive", service.archiveNote)
	mux.HandleFunc("POST /v1/notes/{id}/restore", service.restoreNote)
	mux.HandleFunc("POST /v1/notes/{id}/trash", service.trashNote)
	mux.HandleFunc("GET /v1/labels", service.listLabels)
	mux.HandleFunc("POST /v1/labels", service.createLabel)
	mux.HandleFunc("POST /mcp", service.mcp)

	log.Printf("WearNotes listening on %s", cfg.addr)
	log.Fatal(http.ListenAndServe(cfg.addr, logging(mux)))
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func migrate(db *sql.DB) error {
	_, err := db.Exec(`
CREATE TABLE IF NOT EXISTS notes (
	id TEXT PRIMARY KEY,
	title TEXT NOT NULL DEFAULT '',
	body TEXT NOT NULL DEFAULT '',
	color TEXT NOT NULL DEFAULT 'default',
	pinned INTEGER NOT NULL DEFAULT 0,
	archived INTEGER NOT NULL DEFAULT 0,
	trashed INTEGER NOT NULL DEFAULT 0,
	version INTEGER NOT NULL DEFAULT 1,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	reminder_at INTEGER
);
CREATE TABLE IF NOT EXISTS labels (
	id TEXT PRIMARY KEY,
	name TEXT NOT NULL UNIQUE,
	color TEXT NOT NULL DEFAULT 'default'
);
CREATE TABLE IF NOT EXISTS note_labels (
	note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
	label_id TEXT NOT NULL REFERENCES labels(id) ON DELETE CASCADE,
	PRIMARY KEY(note_id, label_id)
);
CREATE TABLE IF NOT EXISTS attachments (
	id TEXT PRIMARY KEY,
	note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
	filename TEXT NOT NULL,
	mime TEXT NOT NULL,
	path TEXT NOT NULL,
	created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS changes (
	cursor INTEGER PRIMARY KEY AUTOINCREMENT,
	note_id TEXT NOT NULL,
	kind TEXT NOT NULL,
	created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
	id TEXT PRIMARY KEY,
	token_hash TEXT NOT NULL UNIQUE,
	created_at INTEGER NOT NULL,
	last_seen_at INTEGER NOT NULL
);
`)
	return err
}

func (a *api) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (a *api) createDevice(w http.ResponseWriter, _ *http.Request) {
	a.issueDevice(w, "")
}

func (a *api) issueDevice(w http.ResponseWriter, subject string) {
	token := randomID(32)
	id := randomID(16)
	now := time.Now().UnixMilli()
	_, err := a.db.Exec(`INSERT INTO devices(id, token_hash, created_at, last_seen_at) VALUES(?, ?, ?, ?)`,
		id, hash(token), now, now)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"deviceId": id, "token": token, "oidcSubject": subject})
}

func (a *api) oidcConfigEndpoint(w http.ResponseWriter, _ *http.Request) {
	if a.oidcProvider == nil || a.oidcConfig == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"enabled": false})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"enabled":               true,
		"issuer":                a.oidcIssuer,
		"clientId":              a.oidcConfig.ClientID,
		"authorizationEndpoint": a.oidcProvider.Endpoint().AuthURL,
		"tokenEndpoint":         a.oidcProvider.Endpoint().TokenURL,
		"redirectUrl":           a.oidcConfig.RedirectURL,
		"scopes":                a.oidcConfig.Scopes,
		"deviceExchange":        "/v1/auth/oidc/device",
	})
}

func (a *api) oidcDevice(w http.ResponseWriter, r *http.Request) {
	if a.oidcProvider == nil || a.oidcConfig == nil {
		writeError(w, http.StatusNotFound, errors.New("OIDC is not configured"))
		return
	}
	var request struct {
		IDToken string `json:"idToken"`
	}
	if !decodeJSON(w, r, &request) || request.IDToken == "" {
		return
	}
	claims := struct {
		Subject string `json:"sub"`
		Email   string `json:"email"`
	}{}
	verifier := a.oidcProvider.Verifier(&oidc.Config{ClientID: a.oidcConfig.ClientID})
	token, err := verifier.Verify(r.Context(), request.IDToken)
	if err != nil {
		writeError(w, http.StatusUnauthorized, errors.New("invalid OIDC identity token"))
		return
	}
	if err := token.Claims(&claims); err != nil || claims.Subject == "" {
		writeError(w, http.StatusUnauthorized, errors.New("OIDC identity has no subject"))
		return
	}
	a.issueDevice(w, claims.Subject)
}

func (a *api) listNotes(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	state := r.URL.Query().Get("state")
	if state == "" {
		state = "active"
	}
	where := "trashed = 0 AND archived = 0"
	switch state {
	case "archived":
		where = "trashed = 0 AND archived = 1"
	case "trash":
		where = "trashed = 1"
	case "all":
		where = "1 = 1"
	}
	rows, err := a.db.Query(`SELECT id, title, body, color, pinned, archived, trashed, version, created_at, updated_at, reminder_at
		FROM notes WHERE ` + where + ` ORDER BY pinned DESC, updated_at DESC LIMIT 500`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	defer rows.Close()
	notes := make([]note, 0)
	for rows.Next() {
		item, err := scanNote(rows)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		item.Labels = a.labelsFor(item.ID)
		a.hydrateAttachments(&item)
		notes = append(notes, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": notes})
}

func (a *api) searchNotes(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		writeJSON(w, http.StatusOK, map[string]any{"items": []note{}})
		return
	}
	rows, err := a.db.Query(`SELECT id, title, body, color, pinned, archived, trashed, version, created_at, updated_at, reminder_at
		FROM notes WHERE trashed = 0 AND (title LIKE ? OR body LIKE ?) ORDER BY updated_at DESC LIMIT 100`,
		"%"+query+"%", "%"+query+"%")
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	defer rows.Close()
	items := make([]note, 0)
	for rows.Next() {
		item, err := scanNote(rows)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		item.Labels = a.labelsFor(item.ID)
		a.hydrateAttachments(&item)
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (a *api) getNote(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	item, err := a.findNote(r.PathValue("id"))
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, errors.New("note not found"))
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	item.Labels = a.labelsFor(item.ID)
	a.hydrateAttachments(&item)
	writeJSON(w, http.StatusOK, item)
}

func (a *api) createNote(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	var req noteRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	now := time.Now().UnixMilli()
	item := note{
		ID: randomID(16), Title: req.Title, Body: req.Body, Color: valueOr(req.Color, "default"),
		Version: 1, CreatedAt: now, UpdatedAt: now,
	}
	if req.Pinned != nil {
		item.Pinned = *req.Pinned
	}
	if req.Archived != nil {
		item.Archived = *req.Archived
	}
	item.ReminderAt = req.ReminderAt
	_, err := a.db.Exec(`INSERT INTO notes(id,title,body,color,pinned,archived,version,created_at,updated_at,reminder_at) VALUES(?,?,?,?,?,?,?,?,?,?)`,
		item.ID, item.Title, item.Body, item.Color, boolInt(item.Pinned), boolInt(item.Archived), item.Version, item.CreatedAt, item.UpdatedAt, item.ReminderAt)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	a.recordChange(item.ID, "created", now)
	writeJSON(w, http.StatusCreated, item)
}

func (a *api) updateNote(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	var req noteRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	current, err := a.findNote(r.PathValue("id"))
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, errors.New("note not found"))
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	if req.IfVersion != nil && *req.IfVersion != current.Version {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "version conflict", "note": current})
		return
	}
	if req.Title != "" {
		current.Title = req.Title
	}
	if req.Body != "" {
		current.Body = req.Body
	}
	if req.Color != "" {
		current.Color = req.Color
	}
	if req.Pinned != nil {
		current.Pinned = *req.Pinned
	}
	if req.Archived != nil {
		current.Archived = *req.Archived
	}
	if req.ReminderAt != nil {
		current.ReminderAt = req.ReminderAt
	}
	current.Version++
	current.UpdatedAt = time.Now().UnixMilli()
	_, err = a.db.Exec(`UPDATE notes SET title=?, body=?, color=?, pinned=?, archived=?, version=?, updated_at=?, reminder_at=? WHERE id=?`,
		current.Title, current.Body, current.Color, boolInt(current.Pinned), boolInt(current.Archived),
		current.Version, current.UpdatedAt, current.ReminderAt, current.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	a.recordChange(current.ID, "updated", current.UpdatedAt)
	current.Labels = a.labelsFor(current.ID)
	a.hydrateAttachments(&current)
	writeJSON(w, http.StatusOK, current)
}

func (a *api) appendNote(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	var req appendRequest
	if !decodeJSON(w, r, &req) || strings.TrimSpace(req.Text) == "" {
		return
	}
	current, err := a.findNote(r.PathValue("id"))
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, errors.New("note not found"))
		return
	}
	if req.IfVersion != nil && *req.IfVersion != current.Version {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "version conflict", "note": current})
		return
	}
	separator := "\n"
	if current.Body == "" || strings.HasSuffix(current.Body, "\n") {
		separator = ""
	}
	current.Body += separator + req.Text
	current.Version++
	current.UpdatedAt = time.Now().UnixMilli()
	_, err = a.db.Exec(`UPDATE notes SET body=?, version=?, updated_at=? WHERE id=?`,
		current.Body, current.Version, current.UpdatedAt, current.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	a.recordChange(current.ID, "updated", current.UpdatedAt)
	writeJSON(w, http.StatusOK, current)
}

func (a *api) archiveNote(w http.ResponseWriter, r *http.Request) {
	a.setState(w, r, "archived", true)
}

func (a *api) restoreNote(w http.ResponseWriter, r *http.Request) {
	a.setState(w, r, "archived", false)
}

func (a *api) trashNote(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	item, err := a.findNote(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, errors.New("note not found"))
		return
	}
	item.Trashed = true
	item.Version++
	item.UpdatedAt = time.Now().UnixMilli()
	_, err = a.db.Exec(`UPDATE notes SET trashed=1, version=?, updated_at=? WHERE id=?`, item.Version, item.UpdatedAt, item.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	a.recordChange(item.ID, "trashed", item.UpdatedAt)
	writeJSON(w, http.StatusOK, item)
}

func (a *api) deleteNote(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	id := r.PathValue("id")
	result, err := a.db.Exec(`DELETE FROM notes WHERE id=?`, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	count, _ := result.RowsAffected()
	if count == 0 {
		writeError(w, http.StatusNotFound, errors.New("note not found"))
		return
	}
	a.recordChange(id, "deleted", time.Now().UnixMilli())
	w.WriteHeader(http.StatusNoContent)
}

func (a *api) createAttachment(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	noteID := r.PathValue("id")
	if _, err := a.findNote(noteID); err != nil {
		writeError(w, http.StatusNotFound, errors.New("note not found"))
		return
	}
	if err := r.ParseMultipartForm(16 << 20); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, errors.New("file field is required"))
		return
	}
	defer file.Close()
	id := randomID(16)
	filename := filepath.Base(header.Filename)
	if filename == "." || filename == "/" || filename == "" {
		filename = id
	}
	path := filepath.Join(a.data, "attachments", id+"-"+filename)
	output, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	size, copyErr := io.Copy(output, io.LimitReader(file, 25<<20))
	closeErr := output.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(path)
		if copyErr != nil {
			writeError(w, http.StatusInternalServerError, copyErr)
		} else {
			writeError(w, http.StatusInternalServerError, closeErr)
		}
		return
	}
	now := time.Now().UnixMilli()
	attachment := map[string]any{"id": id, "noteId": noteID, "filename": filename, "mime": header.Header.Get("Content-Type"), "size": size}
	_, err = a.db.Exec(`INSERT INTO attachments(id,note_id,filename,mime,path,created_at) VALUES(?,?,?,?,?,?)`,
		id, noteID, filename, header.Header.Get("Content-Type"), path, now)
	if err != nil {
		_ = os.Remove(path)
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	a.recordChange(noteID, "updated", now)
	writeJSON(w, http.StatusCreated, attachment)
}

func (a *api) getAttachment(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	var path, mime string
	err := a.db.QueryRow(`SELECT path, mime FROM attachments WHERE id=?`, r.PathValue("id")).Scan(&path, &mime)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, errors.New("attachment not found"))
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	w.Header().Set("Content-Type", valueOr(mime, "application/octet-stream"))
	http.ServeFile(w, r, path)
}

func (a *api) setState(w http.ResponseWriter, r *http.Request, field string, value bool) {
	if !a.authorized(w, r) {
		return
	}
	item, err := a.findNote(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, errors.New("note not found"))
		return
	}
	item.Archived = value
	item.Version++
	item.UpdatedAt = time.Now().UnixMilli()
	_, err = a.db.Exec(`UPDATE notes SET archived=?, version=?, updated_at=? WHERE id=?`, boolInt(value), item.Version, item.UpdatedAt, item.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	a.recordChange(item.ID, "updated", item.UpdatedAt)
	writeJSON(w, http.StatusOK, item)
}

func (a *api) sync(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	rows, err := a.db.Query(`SELECT cursor, note_id, kind FROM changes WHERE cursor > ? ORDER BY cursor LIMIT 500`, after)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	defer rows.Close()
	changes := make([]map[string]any, 0)
	next := after
	for rows.Next() {
		var cursor int64
		var id, kind string
		if err := rows.Scan(&cursor, &id, &kind); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		next = cursor
		entry := map[string]any{"cursor": cursor, "id": id, "kind": kind}
		if kind != "deleted" {
			item, err := a.findNote(id)
			if err == nil {
				item.Labels = a.labelsFor(id)
				a.hydrateAttachments(&item)
				entry["note"] = item
			}
		}
		changes = append(changes, entry)
	}
	writeJSON(w, http.StatusOK, map[string]any{"cursor": next, "changes": changes, "hasMore": len(changes) == 500})
}

func (a *api) listLabels(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	rows, err := a.db.Query(`SELECT id, name, color FROM labels ORDER BY name`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	defer rows.Close()
	items := make([]label, 0)
	for rows.Next() {
		var item label
		if err := rows.Scan(&item.ID, &item.Name, &item.Color); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (a *api) createLabel(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	var req label
	if !decodeJSON(w, r, &req) {
		return
	}
	item := label{ID: randomID(16), Name: strings.TrimSpace(req.Name), Color: valueOr(req.Color, "default")}
	if item.Name == "" {
		writeError(w, http.StatusBadRequest, errors.New("label name is required"))
		return
	}
	if _, err := a.db.Exec(`INSERT INTO labels(id,name,color) VALUES(?,?,?)`, item.ID, item.Name, item.Color); err != nil {
		writeError(w, http.StatusConflict, errors.New("label already exists"))
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (a *api) mcp(w http.ResponseWriter, r *http.Request) {
	if !a.authorized(w, r) {
		return
	}
	var request struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      any             `json:"id"`
		Method  string          `json:"method"`
		Params  json.RawMessage `json:"params"`
	}
	if !decodeJSON(w, r, &request) {
		return
	}
	response := map[string]any{"jsonrpc": "2.0", "id": request.ID}
	switch request.Method {
	case "initialize":
		response["result"] = map[string]any{
			"protocolVersion": "2025-03-26",
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]string{"name": "wearnotes", "version": "0.1.0"},
		}
	case "notifications/initialized":
		w.WriteHeader(http.StatusAccepted)
		return
	case "tools/list":
		response["result"] = map[string]any{"tools": mcpTools()}
	case "tools/call":
		result, err := a.mcpCall(request.Params)
		if err != nil {
			response["error"] = map[string]any{"code": -32602, "message": err.Error()}
		} else {
			response["result"] = result
		}
	default:
		response["error"] = map[string]any{"code": -32601, "message": "method not found"}
	}
	writeJSON(w, http.StatusOK, response)
}

func (a *api) mcpCall(raw json.RawMessage) (map[string]any, error) {
	var call struct {
		Name      string         `json:"name"`
		Arguments map[string]any `json:"arguments"`
	}
	if err := json.Unmarshal(raw, &call); err != nil {
		return nil, err
	}
	switch call.Name {
	case "list_notes":
		rows, err := a.db.Query(`SELECT id, title, body, color, pinned, archived, trashed, version, created_at, updated_at, reminder_at
			FROM notes WHERE trashed = 0 AND archived = 0 ORDER BY pinned DESC, updated_at DESC LIMIT 500`)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		items := make([]note, 0)
		for rows.Next() {
			item, err := scanNote(rows)
			if err != nil {
				return nil, err
			}
			items = append(items, item)
		}
		return mcpText(items), nil
	case "search_notes":
		query, _ := call.Arguments["query"].(string)
		rows, err := a.db.Query(`SELECT id, title, body, color, pinned, archived, trashed, version, created_at, updated_at, reminder_at
			FROM notes WHERE trashed = 0 AND (title LIKE ? OR body LIKE ?) ORDER BY updated_at DESC LIMIT 100`,
			"%"+query+"%", "%"+query+"%")
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		items := make([]note, 0)
		for rows.Next() {
			item, err := scanNote(rows)
			if err != nil {
				return nil, err
			}
			items = append(items, item)
		}
		return mcpText(items), nil
	case "get_note":
		id, _ := call.Arguments["id"].(string)
		item, err := a.findNote(id)
		if err != nil {
			return nil, err
		}
		return mcpText(item), nil
	case "create_note":
		title, _ := call.Arguments["title"].(string)
		body, _ := call.Arguments["body"].(string)
		now := time.Now().UnixMilli()
		item := note{ID: randomID(16), Title: title, Body: body, Color: "default", Version: 1, CreatedAt: now, UpdatedAt: now}
		_, err := a.db.Exec(`INSERT INTO notes(id,title,body,color,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?)`,
			item.ID, item.Title, item.Body, item.Color, item.Version, item.CreatedAt, item.UpdatedAt)
		if err != nil {
			return nil, err
		}
		a.recordChange(item.ID, "created", now)
		return mcpText(item), nil
	case "update_note":
		id, _ := call.Arguments["id"].(string)
		item, err := a.findNote(id)
		if err != nil {
			return nil, err
		}
		if title, ok := call.Arguments["title"].(string); ok {
			item.Title = title
		}
		if body, ok := call.Arguments["body"].(string); ok {
			item.Body = body
		}
		if color, ok := call.Arguments["color"].(string); ok {
			item.Color = color
		}
		if pinned, ok := call.Arguments["pinned"].(bool); ok {
			item.Pinned = pinned
		}
		item.Version++
		item.UpdatedAt = time.Now().UnixMilli()
		_, err = a.db.Exec(`UPDATE notes SET title=?, body=?, color=?, pinned=?, version=?, updated_at=? WHERE id=?`,
			item.Title, item.Body, item.Color, boolInt(item.Pinned), item.Version, item.UpdatedAt, id)
		if err != nil {
			return nil, err
		}
		a.recordChange(id, "updated", item.UpdatedAt)
		return mcpText(item), nil
	case "append_to_note":
		id, _ := call.Arguments["id"].(string)
		text, _ := call.Arguments["text"].(string)
		item, err := a.findNote(id)
		if err != nil {
			return nil, err
		}
		if item.Body != "" && !strings.HasSuffix(item.Body, "\n") {
			item.Body += "\n"
		}
		item.Body += text
		item.Version++
		item.UpdatedAt = time.Now().UnixMilli()
		_, err = a.db.Exec(`UPDATE notes SET body=?, version=?, updated_at=? WHERE id=?`, item.Body, item.Version, item.UpdatedAt, id)
		if err != nil {
			return nil, err
		}
		a.recordChange(id, "updated", item.UpdatedAt)
		return mcpText(item), nil
	case "archive_note", "restore_note", "trash_note":
		id, _ := call.Arguments["id"].(string)
		item, err := a.findNote(id)
		if err != nil {
			return nil, err
		}
		archived := call.Name == "archive_note"
		trashed := call.Name == "trash_note"
		item.Archived, item.Trashed, item.Version, item.UpdatedAt = archived, trashed, item.Version+1, time.Now().UnixMilli()
		_, err = a.db.Exec(`UPDATE notes SET archived=?, trashed=?, version=?, updated_at=? WHERE id=?`,
			boolInt(item.Archived), boolInt(item.Trashed), item.Version, item.UpdatedAt, id)
		if err != nil {
			return nil, err
		}
		a.recordChange(id, "updated", item.UpdatedAt)
		return mcpText(item), nil
	case "delete_note":
		id, _ := call.Arguments["id"].(string)
		if _, err := a.db.Exec(`DELETE FROM notes WHERE id=?`, id); err != nil {
			return nil, err
		}
		a.recordChange(id, "deleted", time.Now().UnixMilli())
		return mcpText(map[string]bool{"deleted": true}), nil
	case "list_labels":
		rows, err := a.db.Query(`SELECT id, name, color FROM labels ORDER BY name`)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		items := make([]label, 0)
		for rows.Next() {
			var item label
			if err := rows.Scan(&item.ID, &item.Name, &item.Color); err != nil {
				return nil, err
			}
			items = append(items, item)
		}
		return mcpText(items), nil
	default:
		return nil, fmt.Errorf("unknown tool %q", call.Name)
	}
}

func mcpTools() []map[string]any {
	names := []string{"list_notes", "search_notes", "get_note", "create_note", "update_note", "append_to_note", "archive_note", "restore_note", "trash_note", "delete_note", "list_labels"}
	items := make([]map[string]any, 0, len(names))
	for _, name := range names {
		items = append(items, map[string]any{"name": name, "description": "Manage WearNotes notes", "inputSchema": map[string]any{"type": "object"}})
	}
	return items
}

func mcpText(value any) map[string]any {
	data, _ := json.Marshal(value)
	return map[string]any{"content": []map[string]string{{"type": "text", "text": string(data)}}, "isError": false}
}

func (a *api) findNote(id string) (note, error) {
	row := a.db.QueryRow(`SELECT id, title, body, color, pinned, archived, trashed, version, created_at, updated_at, reminder_at FROM notes WHERE id=?`, id)
	return scanNote(row)
}

func scanNote(scanner interface{ Scan(...any) error }) (note, error) {
	var item note
	var pinned, archived, trashed int
	err := scanner.Scan(&item.ID, &item.Title, &item.Body, &item.Color, &pinned, &archived, &trashed,
		&item.Version, &item.CreatedAt, &item.UpdatedAt, &item.ReminderAt)
	item.Pinned, item.Archived, item.Trashed = pinned == 1, archived == 1, trashed == 1
	return item, err
}

func (a *api) labelsFor(noteID string) []label {
	rows, err := a.db.Query(`SELECT l.id, l.name, l.color FROM labels l JOIN note_labels nl ON nl.label_id=l.id WHERE nl.note_id=?`, noteID)
	if err != nil {
		return nil
	}
	defer rows.Close()
	result := make([]label, 0)
	for rows.Next() {
		var item label
		if rows.Scan(&item.ID, &item.Name, &item.Color) == nil {
			result = append(result, item)
		}
	}
	return result
}

func (a *api) hydrateAttachments(item *note) {
	rows, err := a.db.Query(`SELECT id, filename, mime, path FROM attachments WHERE note_id=? ORDER BY created_at`, item.ID)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var attachment attachmentMeta
		var path string
		if rows.Scan(&attachment.ID, &attachment.Filename, &attachment.Mime, &path) == nil {
			if info, err := os.Stat(path); err == nil {
				attachment.Size = info.Size()
			}
			attachment.URL = "/v1/attachments/" + attachment.ID
			item.Attachments = append(item.Attachments, attachment)
		}
	}
	item.PhotoCount = len(item.Attachments)
}

func (a *api) recordChange(noteID, kind string, now int64) {
	_, _ = a.db.Exec(`INSERT INTO changes(note_id,kind,created_at) VALUES(?,?,?)`, noteID, kind, now)
}

func (a *api) authorized(w http.ResponseWriter, r *http.Request) bool {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		writeError(w, http.StatusUnauthorized, errors.New("bearer token required"))
		return false
	}
	tokenHash := hash(strings.TrimPrefix(header, "Bearer "))
	result, err := a.db.Exec(`UPDATE devices SET last_seen_at=? WHERE token_hash=?`, time.Now().UnixMilli(), tokenHash)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return false
	}
	count, _ := result.RowsAffected()
	if count == 0 {
		writeError(w, http.StatusUnauthorized, errors.New("invalid token"))
		return false
	}
	return true
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	defer r.Body.Close()
	if err := json.NewDecoder(r.Body).Decode(target); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

func randomID(bytes int) string {
	buffer := make([]byte, bytes)
	if _, err := rand.Read(buffer); err != nil {
		panic(err)
	}
	return hex.EncodeToString(buffer)
}

func hash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func valueOr(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func boolInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

type recorder struct {
	header http.Header
	status int
	body   []byte
}

func newRecorder() *recorder {
	return &recorder{header: make(http.Header)}
}

func (r *recorder) Header() http.Header    { return r.header }
func (r *recorder) WriteHeader(status int) { r.status = status }
func (r *recorder) Write(data []byte) (int, error) {
	r.body = append(r.body, data...)
	return len(data), nil
}

func logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}
