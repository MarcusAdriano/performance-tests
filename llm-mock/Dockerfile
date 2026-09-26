FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /llm-mock .

FROM alpine:3.24
COPY --from=build /llm-mock /usr/local/bin/llm-mock
EXPOSE 9000
HEALTHCHECK --interval=5s --timeout=2s --retries=5 CMD wget -qO- http://localhost:9000/healthz || exit 1
ENTRYPOINT ["llm-mock"]
