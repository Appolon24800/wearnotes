package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNoteLifecycleAndCursorSync(t *testing.T) {
	db, err := sql.Open("sqlite", "file:wearnotes-test?mode=memory&cache=shared")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := migrate(db); err != nil {
		t.Fatal(err)
	}
	service := &api{db: db, data: t.TempDir()}

	deviceRequest := httptest.NewRequest(http.MethodPost, "/v1/auth/devices", nil)
	deviceResponse := httptest.NewRecorder()
	service.createDevice(deviceResponse, deviceRequest)
	if deviceResponse.Code != http.StatusCreated {
		t.Fatalf("device status = %d", deviceResponse.Code)
	}
	var device struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(deviceResponse.Body.Bytes(), &device); err != nil {
		t.Fatal(err)
	}

	createRequest := httptest.NewRequest(http.MethodPost, "/v1/notes", strings.NewReader(`{"title":"Market","body":"Oranges"}`))
	createRequest.Header.Set("Authorization", "Bearer "+device.Token)
	createResponse := httptest.NewRecorder()
	service.createNote(createResponse, createRequest)
	if createResponse.Code != http.StatusCreated {
		t.Fatalf("create status = %d, body = %s", createResponse.Code, createResponse.Body.String())
	}
	var created note
	if err := json.Unmarshal(createResponse.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}

	syncRequest := httptest.NewRequest(http.MethodGet, "/v1/sync?after=0", nil)
	syncRequest.Header.Set("Authorization", "Bearer "+device.Token)
	syncResponse := httptest.NewRecorder()
	service.sync(syncResponse, syncRequest)
	if syncResponse.Code != http.StatusOK {
		t.Fatalf("sync status = %d", syncResponse.Code)
	}
	var sync struct {
		Cursor  int64            `json:"cursor"`
		Changes []map[string]any `json:"changes"`
	}
	if err := json.Unmarshal(syncResponse.Body.Bytes(), &sync); err != nil {
		t.Fatal(err)
	}
	if sync.Cursor == 0 || len(sync.Changes) != 1 {
		t.Fatalf("unexpected sync response: %+v", sync)
	}
}
