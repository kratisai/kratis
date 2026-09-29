package runner

import (
	"kratis-connector/acp"
	"time"
)

func (s *AgentSupervisor) TestSetTransport(transport *acp.AcpTransport) {
	s.setTransport(transport)
}

func (s *AgentSupervisor) TestSetSession(session *acp.AcpSession) {
	s.setSession(session)
}

func SetTestTimeouts(s *AgentSupervisor) {
	s.Timeouts = SupervisorTimeouts{
		CancelDelay:       10 * time.Millisecond,
		ExitWait:          100 * time.Millisecond,
		SigkillWait:       300 * time.Millisecond,
		DrainWait:         100 * time.Millisecond,
		SessionCloseWait:  200 * time.Millisecond,
		RequestTimeout:    2 * time.Second,
		InitializeTimeout: 2 * time.Second,
	}
}
