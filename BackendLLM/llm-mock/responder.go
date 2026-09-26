package main

import (
	"encoding/json"
	"fmt"
	"time"
)

type Kind string

const (
	KindToolCall Kind = "tool_call"
	KindFinal    Kind = "final"
	KindText     Kind = "text"
)

const maxArgRunes = 50

// Respond produces the mock completion. With tools declared it always drives the
// flow "tool call → final answer": the first call asks for the first declared tool,
// the call carrying the tool result gets the final answer.
func Respond(req ChatRequest, id string, now time.Time) (ChatResponse, Kind) {
	var last Message
	if n := len(req.Messages); n > 0 {
		last = req.Messages[n-1]
	}
	userText := lastUserText(req.Messages)

	switch {
	case last.Role == "tool":
		text := fmt.Sprintf("[mock] Com base na ferramenta: %s (mensagens no contexto: %d)",
			contentText(last.Content), len(req.Messages))
		return completion(req, id, now, ResponseMessage{Role: "assistant", Content: &text}, "stop"), KindFinal
	case len(req.Tools) > 0:
		fn := req.Tools[0].Function
		call := ToolCall{
			ID:       "call_" + id,
			Type:     "function",
			Function: ToolCallFunction{Name: fn.Name, Arguments: argsFromSchema(fn.Parameters, userText)},
		}
		return completion(req, id, now, ResponseMessage{Role: "assistant", ToolCalls: []ToolCall{call}}, "tool_calls"), KindToolCall
	default:
		text := fmt.Sprintf("[mock] Resposta simulada para: %s (mensagens no contexto: %d)",
			truncate(userText, 80), len(req.Messages))
		return completion(req, id, now, ResponseMessage{Role: "assistant", Content: &text}, "stop"), KindText
	}
}

func completion(req ChatRequest, id string, now time.Time, msg ResponseMessage, finishReason string) ChatResponse {
	model := req.Model
	if model == "" {
		model = "llm-mock"
	}
	promptTokens := 0
	for _, m := range req.Messages {
		promptTokens += len(contentText(m.Content))/4 + 1
	}
	const completionTokens = 20
	return ChatResponse{
		ID:      "chatcmpl-" + id,
		Object:  "chat.completion",
		Created: now.Unix(),
		Model:   model,
		Choices: []Choice{{Index: 0, Message: msg, FinishReason: finishReason}},
		Usage:   Usage{PromptTokens: promptTokens, CompletionTokens: completionTokens, TotalTokens: promptTokens + completionTokens},
	}
}

// argsFromSchema builds tool arguments from the tool's JSON Schema, so the mock
// works with any tool without knowing its name.
func argsFromSchema(schema json.RawMessage, userText string) string {
	var s struct {
		Properties map[string]struct {
			Type string `json:"type"`
		} `json:"properties"`
	}
	_ = json.Unmarshal(schema, &s)

	args := make(map[string]any, len(s.Properties))
	for name, prop := range s.Properties {
		switch prop.Type {
		case "number", "integer":
			args[name] = 1
		case "boolean":
			args[name] = true
		default:
			args[name] = truncate(userText, maxArgRunes)
		}
	}
	b, _ := json.Marshal(args)
	return string(b)
}

func lastUserText(msgs []Message) string {
	for i := len(msgs) - 1; i >= 0; i-- {
		if msgs[i].Role == "user" {
			return contentText(msgs[i].Content)
		}
	}
	return ""
}

func truncate(s string, max int) string {
	r := []rune(s)
	if len(r) <= max {
		return s
	}
	return string(r[:max])
}
