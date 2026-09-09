package rpc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

type jsonSchema struct {
	Ref        string                `json:"$ref"`
	Type       any                   `json:"type"`
	Required   []string              `json:"required"`
	Properties map[string]jsonSchema `json:"properties"`
	Enum       []any                 `json:"enum"`
	Items      *jsonSchema           `json:"items"`
}

// openRpcMethod mirrors protocol/environment/openrpc.json method entries.
type openRpcMethod struct {
	Name               string `json:"name"`
	XKratisDirection   string `json:"x-kratis-direction"`
	XKratisMessageKind string `json:"x-kratis-message-kind"`
	Params             []struct {
		Name   string     `json:"name"`
		Schema jsonSchema `json:"schema"`
	} `json:"params"`
	Result *struct {
		Schema jsonSchema `json:"schema"`
	} `json:"result"`
}

func loadOpenRpc(t *testing.T) []openRpcMethod {
	t.Helper()
	candidates := []string{
		filepath.Join("..", "..", "protocol", "environment", "openrpc.json"),
		filepath.Join("..", "protocol", "environment", "openrpc.json"),
	}
	var data []byte
	var err error
	for _, path := range candidates {
		data, err = os.ReadFile(path)
		if err == nil {
			break
		}
	}
	if err != nil {
		t.Fatalf("Cannot locate protocol/environment/openrpc.json: %v", err)
	}

	var doc struct {
		Methods []openRpcMethod `json:"methods"`
	}
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("Failed to parse openrpc.json: %v", err)
	}
	return doc.Methods
}

func loadSchema(t *testing.T, ref string, baseDir string) jsonSchema {
	t.Helper()
	if ref == "" {
		t.Fatal("Schema $ref is empty")
	}
	path := filepath.Join(baseDir, ref)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("Failed to read schema %s: %v", path, err)
	}
	var schema jsonSchema
	if err := json.Unmarshal(data, &schema); err != nil {
		t.Fatalf("Failed to parse schema %s: %v", path, err)
	}
	return schema
}

func structFieldTags(params any) map[string]reflect.StructField {
	result := make(map[string]reflect.StructField)
	if params == nil {
		return result
	}
	typ := reflect.TypeOf(params).Elem()
	if typ.Kind() != reflect.Struct {
		return result
	}
	for i := 0; i < typ.NumField(); i++ {
		field := typ.Field(i)
		tag := field.Tag.Get("json")
		name := field.Name
		if idx := strings.Index(tag, ","); idx >= 0 {
			name = tag[:idx]
		} else if tag != "" {
			name = tag
		}
		if name == "-" {
			continue
		}
		result[name] = field
	}
	return result
}

func hasOmitEmpty(field reflect.StructField) bool {
	tag := field.Tag.Get("json")
	return strings.Contains(tag, ",omitempty")
}

func schemaTypeMatchesGoType(schema jsonSchema, goType reflect.Type) bool {
	// Pointers marshal as their pointed-to value; unwrap before comparing so
	// optional pointer fields (e.g. *ActivityDiff) validate against object.
	for goType.Kind() == reflect.Pointer {
		goType = goType.Elem()
	}
	for _, schemaType := range schemaTypeValues(schema) {
		switch schemaType {
		case "string":
			if goType.Kind() == reflect.String {
				return true
			}
		case "integer":
			if goType.Kind() >= reflect.Int && goType.Kind() <= reflect.Int64 {
				return true
			}
		case "boolean":
			if goType.Kind() == reflect.Bool {
				return true
			}
		case "array":
			if goType.Kind() == reflect.Slice || goType.Kind() == reflect.Array {
				return true
			}
		case "object":
			if goType.Kind() == reflect.Map || goType.Kind() == reflect.Struct {
				return true
			}
		}
	}
	return len(schemaTypeValues(schema)) == 0
}

// Array forms like ["string", "null"] are flattened.
func schemaTypeValues(schema jsonSchema) []string {
	switch t := schema.Type.(type) {
	case string:
		return []string{t}
	case []any:
		var values []string
		for _, v := range t {
			if s, ok := v.(string); ok {
				values = append(values, s)
			}
		}
		return values
	}
	return nil
}

func TestEnvironmentProtocolCompleteness(t *testing.T) {
	methods := loadOpenRpc(t)
	protocolNames := make(map[string]openRpcMethod)
	for _, m := range methods {
		protocolNames[m.Name] = m
	}

	codeNames := make(map[string]MethodDef)
	for _, m := range EnvironmentMethods {
		codeNames[m.Name] = m
	}

	for _, m := range methods {
		if _, ok := codeNames[m.Name]; !ok {
			t.Errorf("Protocol method %s is missing from the closed EnvironmentMethods collection", m.Name)
		}
	}
	for _, m := range EnvironmentMethods {
		if _, ok := protocolNames[m.Name]; !ok {
			t.Errorf("Closed method %s is not defined in protocol/environment/openrpc.json", m.Name)
		}
	}
}

func TestEnvironmentProtocolDirectionAndKind(t *testing.T) {
	methods := loadOpenRpc(t)
	byName := make(map[string]openRpcMethod)
	for _, m := range methods {
		byName[m.Name] = m
	}

	for _, def := range EnvironmentMethods {
		proto, ok := byName[def.Name]
		if !ok {
			continue
		}
		if string(def.Direction) != proto.XKratisDirection {
			t.Errorf("Method %s direction mismatch: code=%s protocol=%s", def.Name, def.Direction, proto.XKratisDirection)
		}
		if string(def.MessageKind) != proto.XKratisMessageKind {
			t.Errorf("Method %s message kind mismatch: code=%s protocol=%s", def.Name, def.MessageKind, proto.XKratisMessageKind)
		}
	}
}

func TestEnvironmentProtocolParamsMatchSchema(t *testing.T) {
	methods := loadOpenRpc(t)
	baseDir := resolveEnvironmentBaseDir(t)

	byName := make(map[string]openRpcMethod)
	for _, m := range methods {
		byName[m.Name] = m
	}

	for _, def := range EnvironmentMethods {
		proto, ok := byName[def.Name]
		if !ok {
			continue
		}

		if len(proto.Params) == 0 {
			if def.Params != nil {
				typ := reflect.TypeOf(def.Params).Elem()
				if typ.NumField() > 0 {
					t.Errorf("Method %s declares no params in protocol but has struct fields", def.Name)
				}
			}
			continue
		}

		param := proto.Params[0]
		schema := param.Schema
		if schema.Ref != "" {
			schema = loadSchema(t, schema.Ref, baseDir)
		}
		validateStructAgainstSchema(t, def.Name+" params", def.Params, schema)
	}
}

func TestEnvironmentProtocolResultsMatchSchemas(t *testing.T) {
	methods := loadOpenRpc(t)
	baseDir := resolveEnvironmentBaseDir(t)

	byName := make(map[string]openRpcMethod)
	for _, m := range methods {
		byName[m.Name] = m
	}

	protocolResultMethods := 0
	for _, def := range EnvironmentMethods {
		proto, ok := byName[def.Name]
		if !ok {
			continue
		}
		if proto.Result == nil || proto.Result.Schema.Ref == "" {
			if def.Result != nil {
				t.Errorf("Method %s has a Go result struct but no protocol result schema", def.Name)
			}
			continue
		}
		protocolResultMethods++
		if def.Result == nil {
			t.Errorf("Method %s has a protocol result schema but no Go result struct", def.Name)
			continue
		}
		schema := loadSchema(t, proto.Result.Schema.Ref, baseDir)
		validateStructAgainstSchema(t, def.Name+" result", def.Result, schema)
	}

	codeResultMethods := 0
	for _, def := range EnvironmentMethods {
		if def.Result != nil {
			codeResultMethods++
		}
	}
	if protocolResultMethods != codeResultMethods {
		t.Errorf("Result-bearing method count mismatch: protocol=%d code=%d", protocolResultMethods, codeResultMethods)
	}
}

func validateStructAgainstSchema(t *testing.T, label string, params any, schema jsonSchema) {
	t.Helper()

	fields := structFieldTags(params)
	schemaProps := make(map[string]jsonSchema)
	for name, prop := range schema.Properties {
		schemaProps[name] = prop
	}

	for name := range schemaProps {
		if _, ok := fields[name]; !ok {
			t.Errorf("%s: schema property %s has no matching Go struct field", label, name)
		}
	}
	for name, field := range fields {
		if _, ok := schemaProps[name]; !ok {
			t.Errorf("%s: Go struct field %s has no matching JSON Schema property", label, name)
			continue
		}
		prop := schemaProps[name]
		if !schemaTypeMatchesGoType(prop, field.Type) {
			t.Errorf("%s: field %s type mismatch: schema=%v go=%s", label, name, schemaTypeValues(prop), field.Type)
		}
		// When the schema declares an enum, the Go field must be a named type
		// (not bare string/int) so that the compiler enforces the closed set.
		// A plain string field passes reflect.String but carries no compile-time
		// constraint — exactly the gap this check closes.
		if len(prop.Enum) > 0 {
			goType := field.Type
			for goType.Kind() == reflect.Pointer {
				goType = goType.Elem()
			}
			if goType.PkgPath() == "" {
				// PkgPath is empty for predeclared types (string, int, bool, …).
				// Named types defined in a package always have a non-empty PkgPath.
				t.Errorf("%s: schema enum field %s must be backed by a named Go type (e.g. type Foo string), not bare %s", label, name, goType.Name())
			}
		}
	}

	for _, required := range schema.Required {
		field, ok := fields[required]
		if !ok {
			t.Errorf("%s: required property %s has no Go struct field", label, required)
			continue
		}
		if hasOmitEmpty(field) {
			t.Errorf("%s: required property %s should not use omitempty", label, required)
		}
	}
}

func resolveEnvironmentBaseDir(t *testing.T) string {
	t.Helper()
	baseDir := filepath.Join("..", "..", "protocol", "environment")
	if _, err := os.Stat(baseDir); err != nil {
		baseDir = filepath.Join("..", "protocol", "environment")
	}
	return baseDir
}

func TestEnvironmentValidFixturesRoundTrip(t *testing.T) {
	methods := loadOpenRpc(t)
	byName := make(map[string]openRpcMethod)
	for _, m := range methods {
		byName[m.Name] = m
	}

	methodDefs := make(map[string]MethodDef)
	for _, m := range EnvironmentMethods {
		methodDefs[m.Name] = m
	}

	validDir := filepath.Join("..", "..", "protocol", "environment", "examples", "valid")
	if _, err := os.Stat(validDir); err != nil {
		validDir = filepath.Join("..", "protocol", "environment", "examples", "valid")
	}
	files, err := filepath.Glob(filepath.Join(validDir, "*.json"))
	if err != nil {
		t.Fatalf("Failed to list valid fixtures: %v", err)
	}

	covered := make(map[string]bool)
	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatalf("Failed to read %s: %v", file, err)
		}

		var req JsonRpcRequest
		if err := json.Unmarshal(data, &req); err != nil {
			t.Fatalf("Failed to unmarshal %s: %v", file, err)
		}

		def, ok := methodDefs[req.Method]
		if !ok {
			t.Errorf("Fixture %s references unknown method %s", file, req.Method)
			continue
		}
		covered[req.Method] = true

		if !hasStructFields(def.Params) {
			if req.Params != nil {
				t.Errorf("Fixture %s for method %s should not have params", file, req.Method)
			}
			continue
		}

		if req.Params == nil {
			t.Errorf("Fixture %s for method %s is missing params", file, req.Method)
			continue
		}

		paramsBytes, err := json.Marshal(req.Params)
		if err != nil {
			t.Fatalf("Failed to marshal params in %s: %v", file, err)
		}

		schema := byName[req.Method].Params[0].Schema
		if schema.Ref != "" {
			baseDir := filepath.Join("..", "..", "protocol", "environment")
			if _, err := os.Stat(baseDir); err != nil {
				baseDir = filepath.Join("..", "protocol", "environment")
			}
			schema = loadSchema(t, schema.Ref, baseDir)
		}

		paramsVal := reflect.New(reflect.TypeOf(def.Params).Elem())
		if err := validateParamsAgainstSchema(t, file, schema, paramsBytes, paramsVal.Elem()); err != nil {
			t.Errorf("Fixture %s failed schema validation: %v", file, err)
		}
	}

	for _, m := range methods {
		if !covered[m.Name] {
			t.Errorf("Protocol method %s has no valid example fixture", m.Name)
		}
	}
}

func TestEnvironmentInvalidFixturesAreRejected(t *testing.T) {
	methods := loadOpenRpc(t)
	byName := make(map[string]openRpcMethod)
	for _, m := range methods {
		byName[m.Name] = m
	}

	methodDefs := make(map[string]MethodDef)
	for _, m := range EnvironmentMethods {
		methodDefs[m.Name] = m
	}

	invalidDir := filepath.Join("..", "..", "protocol", "environment", "examples", "invalid")
	if _, err := os.Stat(invalidDir); err != nil {
		invalidDir = filepath.Join("..", "protocol", "environment", "examples", "invalid")
	}
	files, err := filepath.Glob(filepath.Join(invalidDir, "*.json"))
	if err != nil {
		t.Fatalf("Failed to list invalid fixtures: %v", err)
	}
	if len(files) == 0 {
		return
	}

	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatalf("Failed to read %s: %v", file, err)
		}

		var req JsonRpcRequest
		if err := json.Unmarshal(data, &req); err != nil {
			t.Fatalf("Failed to unmarshal %s: %v", file, err)
		}

		def, ok := methodDefs[req.Method]
		if !ok || !hasStructFields(def.Params) {
			continue
		}

		if req.Params == nil {
			continue
		}

		schema := byName[req.Method].Params[0].Schema
		if schema.Ref != "" {
			baseDir := filepath.Join("..", "..", "protocol", "environment")
			if _, err := os.Stat(baseDir); err != nil {
				baseDir = filepath.Join("..", "protocol", "environment")
			}
			schema = loadSchema(t, schema.Ref, baseDir)
		}

		paramsBytes, _ := json.Marshal(req.Params)
		paramsVal := reflect.New(reflect.TypeOf(def.Params).Elem())
		if err := validateParamsAgainstSchema(t, file, schema, paramsBytes, paramsVal.Elem()); err != nil {
			continue // rejected by schema validation
		}

		t.Errorf("Invalid fixture %s was accepted by the Go struct", file)
	}
}

func hasStructFields(params any) bool {
	if params == nil {
		return false
	}
	typ := reflect.TypeOf(params).Elem()
	return typ.Kind() == reflect.Struct && typ.NumField() > 0
}

func validateParamsAgainstSchema(t *testing.T, _ string, schema jsonSchema, data []byte, paramsVal reflect.Value) error {
	t.Helper()
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}

	for name := range raw {
		if _, ok := schema.Properties[name]; !ok {
			return errUnknownField(name)
		}
	}

	for _, required := range schema.Required {
		if _, ok := raw[required]; !ok {
			return errMissingRequired(required)
		}
	}

	for name, prop := range schema.Properties {
		rawValue, ok := raw[name]
		if !ok {
			continue
		}

		field := paramsVal.FieldByNameFunc(func(n string) bool {
			f, ok := paramsVal.Type().FieldByName(n)
			if !ok {
				return false
			}
			return jsonFieldName(f) == name
		})
		if !field.IsValid() {
			continue
		}

		if err := json.Unmarshal(rawValue, field.Addr().Interface()); err != nil {
			return err
		}

		if len(prop.Enum) > 0 {
			str := field.String()
			valid := false
			for _, e := range prop.Enum {
				if s, ok := e.(string); ok && s == str {
					valid = true
					break
				}
			}
			if !valid {
				return errInvalidEnum(name, str)
			}
		}
	}
	return nil
}

func jsonFieldName(f reflect.StructField) string {
	tag := f.Tag.Get("json")
	if idx := strings.Index(tag, ","); idx >= 0 {
		return tag[:idx]
	}
	if tag != "" {
		return tag
	}
	return f.Name
}

func errUnknownField(name string) error { return &validationError{msg: "unknown field: " + name} }
func errMissingRequired(name string) error {
	return &validationError{msg: "missing required field: " + name}
}
func errInvalidEnum(field, value string) error {
	return &validationError{msg: "invalid enum value for " + field + ": " + value}
}

type validationError struct{ msg string }

func (e *validationError) Error() string { return e.msg }

func TestEnvironmentInboundDispatchIsComplete(t *testing.T) {
	clientGoPaths := []string{
		"client.go",
		filepath.Join("rpc", "client.go"),
	}
	var clientGo []byte
	var err error
	for _, path := range clientGoPaths {
		clientGo, err = os.ReadFile(path)
		if err == nil {
			break
		}
	}
	if err != nil {
		t.Fatalf("Cannot read client.go for dispatch validation: %v", err)
	}
	clientGoStr := string(clientGo)

	for _, def := range EnvironmentMethods {
		if def.Direction != ControlPlaneToConnector {
			continue
		}
		caseLiteral := `case "` + def.Name + `"`
		if !strings.Contains(clientGoStr, caseLiteral) {
			t.Errorf("Inbound sidecar dispatch switch is missing case for %s", def.Name)
		}
	}
}
