FROM golang:1.25-alpine AS builder

ARG BUILD_PACKAGE=./cmd/app
ENV CGO_ENABLED=0 \
    GOOS=linux

WORKDIR /build

COPY go.mod ./
RUN go mod download

COPY . .
RUN go build -trimpath -ldflags="-s -w" -o /build/app "$BUILD_PACKAGE"

FROM scratch

COPY --from=builder /build/app .

EXPOSE 8081
CMD ["/app"]
