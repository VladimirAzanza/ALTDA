package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/VladimirAzanza/ALTDA/go-service/internal/app"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	application := app.New()
	if err := application.Run(ctx); err != nil {
		slog.ErrorContext(ctx, "service stopped with error", slog.Any("error", err))
		os.Exit(1)
	}
}
