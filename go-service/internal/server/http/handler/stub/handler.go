// Package stub answers 501 for contract routes that have no real handler yet.
// Delete each route from here when its handler package is added.
package stub

import (
	"net/http"

	httperror "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/error"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/response"
)

var routes = []string{
	"POST /v1/parse",
	"POST /v1/skeleton/generate",
	"POST /v1/skeleton/regenerate",
	"POST /v1/allocate",
	"POST /v1/content",
	"POST /v1/validate",
}

type Handler struct{}

func NewHandler() *Handler {
	return &Handler{}
}

func (h *Handler) Register(mux *http.ServeMux) {
	for _, route := range routes {
		mux.HandleFunc(route, h.NotImplemented)
	}
}

func (h *Handler) NotImplemented(w http.ResponseWriter, r *http.Request) {
	response.Error(w, r, httperror.New(httperror.NotImplemented, r.Method+" "+r.URL.Path+" is not implemented yet"))
}
