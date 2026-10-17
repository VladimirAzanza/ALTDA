package skeleton

import "errors"

var (
	// ErrInvalidProgram: the request lacks what the skeleton needs (e.g. totalHours).
	ErrInvalidProgram = errors.New("invalid program")

	// ErrNoValidVariant: the drafter returned nothing usable.
	ErrNoValidVariant = errors.New("no valid skeleton variant could be produced")
)
