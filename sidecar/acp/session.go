package acp

type AcpSession struct {
	SessionID    string
	AgentName    string
	AgentVersion string

	ProtocolVersion   int
	AgentTitle        string
	AgentCapabilities AcpAgentCapabilities
	AuthMethods       []interface{}

	Transport *AcpTransport
}
