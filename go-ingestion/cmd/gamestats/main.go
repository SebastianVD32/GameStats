package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/SebastianVD32/GameStats/go-ingestion/internal/delivery"
	"github.com/SebastianVD32/GameStats/go-ingestion/internal/eventsource"
	"github.com/SebastianVD32/GameStats/go-ingestion/internal/httpapi"
	"github.com/SebastianVD32/GameStats/go-ingestion/internal/ingestion"
)

func main() {
	if err := run(); err != nil {
		slog.Error("el servicio termino con un error", "error", err)
		os.Exit(1)
	}
}

func run() error {
	address := flag.String("address", envString("HTTP_ADDR", ":8080"), "direccion HTTP")
	workers := flag.Int("workers", envInt("WORKER_COUNT", runtime.NumCPU()), "cantidad de workers por partida")
	inputPath := flag.String("input", "", "archivo JSON o JSON Lines para cargar al iniciar")
	serve := flag.Bool("serve", true, "mantener el servidor HTTP despues de cargar el archivo")
	analyzerURL := flag.String("analyzer-url", os.Getenv("ANALYZER_URL"), "endpoint HTTP del analizador Scala")
	outputPath := flag.String("output", envString("OUTPUT_PATH", "data/ready-matches.jsonl"), "salida JSON Lines si no se configura el analizador")
	flag.Parse()

	sink, err := buildSink(*analyzerURL, *outputPath)
	if err != nil {
		return err
	}
	processor, err := ingestion.NewProcessor(*workers, sink)
	if err != nil {
		return err
	}
	defer processor.Close()

	if *inputPath != "" {
		if err := loadInput(context.Background(), processor, *inputPath); err != nil {
			return err
		}
	}
	if !*serve {
		return nil
	}

	server := &http.Server{
		Addr:              *address,
		Handler:           httpapi.NewHandler(processor),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	serverErrors := make(chan error, 1)
	go func() {
		slog.Info("servicio de ingestion iniciado", "address", *address, "workers", *workers)
		serverErrors <- server.ListenAndServe()
	}()

	signalContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	select {
	case <-signalContext.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return server.Shutdown(shutdownContext)
	case err := <-serverErrors:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func buildSink(analyzerURL, outputPath string) (delivery.Sink, error) {
	if strings.TrimSpace(analyzerURL) != "" {
		sink, err := delivery.NewHTTPSink(analyzerURL, nil)
		if err != nil {
			return nil, err
		}
		slog.Info("las partidas terminadas se enviaran por HTTP", "endpoint", analyzerURL)
		return sink, nil
	}
	sink, err := delivery.NewFileSink(outputPath)
	if err != nil {
		return nil, err
	}
	slog.Info("las partidas terminadas se escribiran como JSON Lines", "path", outputPath)
	return sink, nil
}

func loadInput(ctx context.Context, processor *ingestion.Processor, path string) error {
	events, err := eventsource.LoadFile(path)
	if err != nil {
		return err
	}
	for index, event := range events {
		if _, err := processor.Process(ctx, event); err != nil {
			return fmt.Errorf("procesar evento %d (%s): %w", index, event.EventID, err)
		}
	}
	slog.Info("archivo de eventos procesado", "path", path, "events", len(events))
	return nil
}

func envString(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envInt(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}
