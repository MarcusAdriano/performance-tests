package main

import (
	"context"
	crand "crypto/rand"
	"encoding/hex"
	"encoding/json"
	"math/rand/v2"
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type Server struct {
	cfg      Config
	registry *prometheus.Registry

	// Replaceable in tests.
	rand   func() float64
	sleep  func(ctx context.Context, d time.Duration) error
	now    func() time.Time
	nextID func() string

	requests *prometheus.CounterVec
	inflight prometheus.Gauge
	delay    prometheus.Histogram
}

func NewServer(cfg Config, registry *prometheus.Registry) *Server {
	s := &Server{
		cfg:      cfg,
		registry: registry,
		rand:     rand.Float64,
		sleep:    sleepCtx,
		now:      time.Now,
		nextID:   randomID,
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "llm_mock_requests_total",
			Help: "Chat completion requests by response type.",
		}, []string{"type"}),
		inflight: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "llm_mock_inflight_requests",
			Help: "Chat completion requests currently 'thinking'.",
		}),
		delay: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name:    "llm_mock_delay_seconds",
			Help:    "Simulated LLM latency.",
			Buckets: prometheus.LinearBuckets(0.5, 0.5, 12),
		}),
	}
	registry.MustRegister(s.requests, s.inflight, s.delay)
	return s
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	// Spring AI uses the configured base URL as-is; serve both forms.
	mux.HandleFunc("POST /v1/chat/completions", s.chatCompletions)
	mux.HandleFunc("POST /chat/completions", s.chatCompletions)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok")) })
	mux.Handle("GET /metrics", promhttp.HandlerFor(s.registry, promhttp.HandlerOpts{}))
	return mux
}

func (s *Server) chatCompletions(w http.ResponseWriter, r *http.Request) {
	s.inflight.Inc()
	defer s.inflight.Dec()

	var req ChatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.requests.WithLabelValues("bad_request").Inc()
		writeError(w, http.StatusBadRequest, "invalid_request_error", err.Error())
		return
	}

	d := s.delayFor()
	if err := s.sleep(r.Context(), d); err != nil {
		// The client gave up (timeout/cancel): stop now so no phantom work piles up.
		s.requests.WithLabelValues("cancelled").Inc()
		return
	}
	s.delay.Observe(d.Seconds())

	if s.cfg.ErrorRate > 0 && s.rand() < s.cfg.ErrorRate {
		s.requests.WithLabelValues("error").Inc()
		if s.rand() < 0.5 {
			writeError(w, http.StatusInternalServerError, "server_error", "simulated failure")
		} else {
			writeError(w, http.StatusTooManyRequests, "rate_limit_error", "simulated rate limit")
		}
		return
	}

	resp, kind := Respond(req, s.nextID(), s.now())
	s.requests.WithLabelValues(string(kind)).Inc()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func (s *Server) delayFor() time.Duration {
	span := s.cfg.MaxDelay - s.cfg.MinDelay
	return s.cfg.MinDelay + time.Duration(s.rand()*float64(span))
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func randomID() string {
	b := make([]byte, 12)
	_, _ = crand.Read(b)
	return hex.EncodeToString(b)
}

func writeError(w http.ResponseWriter, status int, errType, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"message": message, "type": errType}})
}
