package app

import (
	"context"
	"log/slog"
	"os"
	"strings"

	httpserver "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http"
	allocatehandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/allocate"
	healthhandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/health"
	parsehandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/parse"
	skeletonhandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/skeleton"
	stubhandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/stub"
	validatehandler "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/handler/validate"
	allocateservice "github.com/VladimirAzanza/ALTDA/go-service/internal/service/allocate"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/service/allocator"
	parseservice "github.com/VladimirAzanza/ALTDA/go-service/internal/service/parse"
	skeletonservice "github.com/VladimirAzanza/ALTDA/go-service/internal/service/skeleton"
	validateservice "github.com/VladimirAzanza/ALTDA/go-service/internal/service/validate"
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
		parsehandler.NewHandler(parseservice.NewService()),
		allocatehandler.NewHandler(allocateservice.NewService(allocator.DefaultConfig())),
		validatehandler.NewHandler(validateservice.NewService()),
		skeletonhandler.NewHandler(skeletonservice.NewService(skeletonservice.NewTemplateDrafter(), allocator.DefaultConfig())),
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
