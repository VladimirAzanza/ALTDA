package validate

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/VladimirAzanza/ALTDA/go-service/internal/contract"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/program"
	httperror "github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/error"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/server/http/response"
	validateservice "github.com/VladimirAzanza/ALTDA/go-service/internal/service/validate"
)

const maxBodyBytes = 32 << 20 // a full program with content is a few MiB

type Handler struct {
	validateService validateservice.Service
}

func NewHandler(validateService validateservice.Service) *Handler {
	return &Handler{validateService: validateService}
}

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/validate", h.Validate)
}

type validateRequest struct {
	SchemaVersion string           `json:"schemaVersion"`
	Program       *program.Program `json:"program"`
}

type validateResponse struct {
	SchemaVersion    string                  `json:"schemaVersion"`
	ValidationReport *validateservice.Report `json:"validationReport"`
	Issues           []issue                 `json:"issues"`
}

// issue is a failed check as the contract lists it in "issues".
type issue struct {
	RuleID   string                   `json:"ruleId"`
	Severity validateservice.Severity `json:"severity"`
	Message  string                   `json:"message"`
	Path     string                   `json:"path"`
}

func (h *Handler) Validate(w http.ResponseWriter, r *http.Request) {
	var req validateRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes)).Decode(&req); err != nil {
		response.Error(w, r, httperror.Wrap(httperror.BadRequest, "request body is not valid JSON", err))
		return
	}
	if req.SchemaVersion != contract.SchemaVersion {
		response.Error(w, r, httperror.New(httperror.BadRequest, "schemaVersion must be "+contract.SchemaVersion))
		return
	}
	if req.Program == nil {
		response.Error(w, r, httperror.New(httperror.BadRequest, "program is required"))
		return
	}

	report, err := h.validateService.Validate(r.Context(), req.Program)
	if err != nil {
		response.Error(w, r, toHTTPError(err))
		return
	}

	issues := []issue{} // never null, as the contract requires
	for _, failed := range report.Issues() {
		issues = append(issues, issue{
			RuleID:   failed.RuleID,
			Severity: failed.Severity,
			Message:  failed.Message,
			Path:     failed.Path,
		})
	}

	response.JSON(w, http.StatusOK, validateResponse{
		SchemaVersion:    contract.SchemaVersion,
		ValidationReport: report,
		Issues:           issues,
	})
}

func toHTTPError(err error) error {
	if errors.Is(err, validateservice.ErrInvalidProgram) {
		return httperror.Wrap(httperror.BadRequest, err.Error(), err)
	}
	return err
}
