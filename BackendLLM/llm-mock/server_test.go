package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

const toolRequest = `{"model":"mock-model","messages":[{"role":"user","content":"Recife"}],
"tools":[{"type":"function","function":{"name":"consultarPrevisaoTempo","parameters":{"type":"object","properties":{"cidade":{"type":"string"}}}}}]}`

func newTestServer(cfg Config) (*Server, *[]time.Duration) {
	s := NewServer(cfg, prometheus.NewRegistry())
	var slept []time.Duration
	s.sleep = func(ctx context.Context, d time.Duration) error {
		slept = append(slept, d)
		return ctx.Err()
	}
	s.rand = func() float64 { return 0.5 }
	s.nextID = func() string { return "fixed" }
	return s, &slept
}

func post(h http.Handler, path, body string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, path, strings.NewReader(body)))
	return rec
}

func TestChatCompletionsReturnsOpenAIToolCall(t *testing.T) {
	s, slept := newTestServer(Config{MinDelay: 2 * time.Second, MaxDelay: 4 * time.Second})

	rec := post(s.Handler(), "/v1/chat/completions", toolRequest)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content-type = %s", ct)
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	choice := body["choices"].([]any)[0].(map[string]any)
	if choice["finish_reason"] != "tool_calls" {
		t.Fatalf("finish_reason = %v", choice["finish_reason"])
	}
	if _, ok := choice["logprobs"]; !ok {
		t.Error("choice must contain logprobs key")
	}
	msg := choice["message"].(map[string]any)
	// OpenAI clients expect these keys even when null.
	for _, k := range []string{"content", "refusal"} {
		if _, ok := msg[k]; !ok {
			t.Errorf("message must contain %q key", k)
		}
	}
	if got := (*slept)[0]; got != 3*time.Second {
		t.Fatalf("slept %s, want 3s (min + 0.5*(max-min))", got)
	}
}

func TestChatCompletionsAcceptsPathWithoutV1(t *testing.T) {
	s, _ := newTestServer(Config{})
	if rec := post(s.Handler(), "/chat/completions", toolRequest); rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestChatCompletionsInjectsErrors(t *testing.T) {
	s, _ := newTestServer(Config{ErrorRate: 1.0})

	rec := post(s.Handler(), "/v1/chat/completions", toolRequest)

	if rec.Code != http.StatusInternalServerError && rec.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 500 or 429", rec.Code)
	}
	if got := testutil.ToFloat64(s.requests.WithLabelValues("error")); got != 1 {
		t.Fatalf("error counter = %v", got)
	}
}

func TestChatCompletionsStopsWhenClientCancels(t *testing.T) {
	s, _ := newTestServer(Config{MinDelay: time.Second, MaxDelay: time.Second})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(toolRequest)).WithContext(ctx)
	rec := httptest.NewRecorder()

	s.Handler().ServeHTTP(rec, req)

	if rec.Body.Len() != 0 {
		t.Fatalf("expected empty body, got %s", rec.Body)
	}
	if got := testutil.ToFloat64(s.requests.WithLabelValues("cancelled")); got != 1 {
		t.Fatalf("cancelled counter = %v", got)
	}
}

func TestChatCompletionsRejectsInvalidJSON(t *testing.T) {
	s, _ := newTestServer(Config{})
	if rec := post(s.Handler(), "/v1/chat/completions", "{"); rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestDelayStaysWithinConfiguredRange(t *testing.T) {
	s, _ := newTestServer(Config{MinDelay: 2 * time.Second, MaxDelay: 5 * time.Second})

	s.rand = func() float64 { return 0 }
	if d := s.delayFor(); d != 2*time.Second {
		t.Fatalf("min delay = %s", d)
	}
	s.rand = func() float64 { return 0.999999 }
	if d := s.delayFor(); d < 4900*time.Millisecond || d >= 5*time.Second {
		t.Fatalf("max delay = %s", d)
	}
}

func TestMetricsEndpointExposesCounters(t *testing.T) {
	s, _ := newTestServer(Config{})
	post(s.Handler(), "/v1/chat/completions", toolRequest)

	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/metrics", nil))

	body := rec.Body.String()
	for _, want := range []string{`llm_mock_requests_total{type="tool_call"} 1`, "llm_mock_inflight_requests", "llm_mock_delay_seconds_bucket"} {
		if !strings.Contains(body, want) {
			t.Errorf("metrics missing %q", want)
		}
	}
}

func TestHealthz(t *testing.T) {
	s, _ := newTestServer(Config{})
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
}
