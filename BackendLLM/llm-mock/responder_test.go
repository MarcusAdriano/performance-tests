package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

var weatherTool = Tool{Type: "function", Function: ToolFunction{
	Name:       "consultarPrevisaoTempo",
	Parameters: json.RawMessage(`{"type":"object","properties":{"cidade":{"type":"string"}},"required":["cidade"]}`),
}}

func raw(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func TestRespondCallsFirstToolWhenLastMessageIsFromUser(t *testing.T) {
	req := ChatRequest{
		Model: "mock-model",
		Tools: []Tool{weatherTool, {Type: "function", Function: ToolFunction{Name: "outraTool"}}},
		Messages: []Message{
			{Role: "system", Content: raw("sys")},
			{Role: "user", Content: raw("Porto Alegre")},
		},
	}

	resp, kind := Respond(req, "abc", time.Unix(100, 0))

	if kind != KindToolCall {
		t.Fatalf("kind = %s, want %s", kind, KindToolCall)
	}
	if resp.Object != "chat.completion" || resp.Model != "mock-model" || resp.Created != 100 || resp.ID != "chatcmpl-abc" {
		t.Fatalf("unexpected envelope: %+v", resp)
	}
	choice := resp.Choices[0]
	if choice.FinishReason != "tool_calls" {
		t.Fatalf("finish_reason = %s, want tool_calls", choice.FinishReason)
	}
	if choice.Message.Content != nil {
		t.Fatalf("content should be null on a tool call")
	}
	calls := choice.Message.ToolCalls
	if len(calls) != 1 || calls[0].Function.Name != "consultarPrevisaoTempo" || calls[0].Type != "function" || calls[0].ID != "call_abc" {
		t.Fatalf("unexpected tool calls: %+v", calls)
	}
	var args map[string]any
	if err := json.Unmarshal([]byte(calls[0].Function.Arguments), &args); err != nil {
		t.Fatalf("arguments are not JSON: %v", err)
	}
	if args["cidade"] != "Porto Alegre" {
		t.Fatalf("cidade = %v, want Porto Alegre", args["cidade"])
	}
}

func TestRespondReturnsFinalAnswerAfterToolResult(t *testing.T) {
	req := ChatRequest{
		Tools: []Tool{weatherTool},
		Messages: []Message{
			{Role: "user", Content: raw("Porto Alegre")},
			{Role: "assistant", ToolCalls: []ToolCall{{ID: "call_1", Type: "function", Function: ToolCallFunction{Name: "consultarPrevisaoTempo", Arguments: `{}`}}}},
			{Role: "tool", ToolCallID: "call_1", Content: raw("Previsão para Porto Alegre: 20°C")},
		},
	}

	resp, kind := Respond(req, "abc", time.Now())

	if kind != KindFinal {
		t.Fatalf("kind = %s, want %s", kind, KindFinal)
	}
	choice := resp.Choices[0]
	if choice.FinishReason != "stop" || choice.Message.Content == nil {
		t.Fatalf("unexpected choice: %+v", choice)
	}
	content := *choice.Message.Content
	if !strings.Contains(content, "Previsão para Porto Alegre: 20°C") || !strings.Contains(content, "mensagens no contexto: 3") {
		t.Fatalf("unexpected content: %s", content)
	}
}

func TestRespondReturnsPlainTextWithoutTools(t *testing.T) {
	req := ChatRequest{Messages: []Message{{Role: "user", Content: raw("Olá")}}}

	resp, kind := Respond(req, "abc", time.Now())

	if kind != KindText {
		t.Fatalf("kind = %s, want %s", kind, KindText)
	}
	if resp.Model != "llm-mock" {
		t.Fatalf("model = %s, want llm-mock default", resp.Model)
	}
	if c := resp.Choices[0].Message.Content; c == nil || !strings.Contains(*c, "Olá") {
		t.Fatalf("unexpected content: %v", c)
	}
}

func TestArgsFromSchemaFillsByType(t *testing.T) {
	schema := json.RawMessage(`{"type":"object","properties":{"s":{"type":"string"},"n":{"type":"number"},"i":{"type":"integer"},"b":{"type":"boolean"}}}`)

	var args map[string]any
	if err := json.Unmarshal([]byte(argsFromSchema(schema, "texto")), &args); err != nil {
		t.Fatal(err)
	}

	if args["s"] != "texto" || args["n"] != float64(1) || args["i"] != float64(1) || args["b"] != true {
		t.Fatalf("unexpected args: %v", args)
	}
}

func TestArgsFromSchemaTruncatesLongText(t *testing.T) {
	schema := json.RawMessage(`{"type":"object","properties":{"s":{"type":"string"}}}`)

	var args map[string]any
	_ = json.Unmarshal([]byte(argsFromSchema(schema, strings.Repeat("á", 80))), &args)

	if n := len([]rune(args["s"].(string))); n != 50 {
		t.Fatalf("len = %d, want 50", n)
	}
}

func TestContentTextAcceptsArrayOfParts(t *testing.T) {
	got := contentText(json.RawMessage(`[{"type":"text","text":"Olá "},{"type":"text","text":"mundo"}]`))
	if got != "Olá mundo" {
		t.Fatalf("got %q", got)
	}
}
