package app

import (
	"context"
	"log/slog"
	"os"
	"strings"

	httpserver "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http"
	healthhandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/health"
	stubhandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/stub"
)

type App struct {
	config *Config
}

func New() *App {
	config := NewConfig()
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: parseLogLevel(config.LogLevel),
	})))

	return &App{config: config}
}

func (app *App) Run(ctx context.Context) error {
	server := httpserver.NewServer(
		httpserver.NewConfig(),
		healthhandler.NewHandler(),
		stubhandler.NewHandler(),
	)

	return server.Run(ctx)
}

func parseLogLevel(level string) slog.Level {
	var parsed slog.Level
	if err := parsed.UnmarshalText([]byte(strings.ToUpper(level))); err != nil {
		return slog.LevelInfo
	}
	return parsed
}
