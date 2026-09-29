package geometry

// Shape is satisfied implicitly by any type with Area and Perimeter methods.
type Shape interface {
	Area() float64
	Perimeter() float64
}
