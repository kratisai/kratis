package rpc

// MessageKind mirrors x-kratis-message-kind in protocol/environment/openrpc.json.
type MessageKind string

const (
	Request      MessageKind = "request"
	Notification MessageKind = "notification"
)

// Direction mirrors x-kratis-direction in protocol/environment/openrpc.json.
type Direction string

const (
	ConnectorToControlPlane Direction = "connector-to-control-plane"
	ControlPlaneToConnector Direction = "control-plane-to-connector"
)

// MethodDef describes one environment JSON-RPC method. The closed set of records
// in this package is validated against protocol/environment/openrpc.json by
// ProtocolConformanceTest so any drift fails the build.
type MethodDef struct {
	Name string
	MessageKind
	Direction
	Params any
	Result any
}

// EnvironmentMethods is the closed collection of every env.* method.
var EnvironmentMethods = []MethodDef{
	{Name: "env.register", MessageKind: Request, Direction: ConnectorToControlPlane, Params: &RegisterParams{}, Result: &RegisterResult{}},
	{Name: "env.heartbeat", MessageKind: Request, Direction: ConnectorToControlPlane, Params: &HeartbeatParams{}, Result: &HeartbeatResult{}},
	{Name: "env.git_token", MessageKind: Request, Direction: ConnectorToControlPlane, Params: &GitTokenParams{}, Result: &GitTokenResult{}},
	{Name: "env.output", MessageKind: Notification, Direction: ConnectorToControlPlane, Params: &OutputParams{}},
	{Name: "env.sidecar_error", MessageKind: Notification, Direction: ConnectorToControlPlane, Params: &SidecarErrorParams{}},
	{Name: "env.activity", MessageKind: Notification, Direction: ConnectorToControlPlane, Params: &ActivityParams{}},
	{Name: "env.complete", MessageKind: Notification, Direction: ConnectorToControlPlane, Params: &CompleteParams{}},
	{Name: "env.checkout_complete", MessageKind: Notification, Direction: ConnectorToControlPlane, Params: &CheckoutCompleteParams{}},
	{Name: "env.acp_initialized", MessageKind: Notification, Direction: ConnectorToControlPlane, Params: &AcpInitializedParams{}},
	{Name: "env.acp_prompt_complete", MessageKind: Notification, Direction: ConnectorToControlPlane, Params: &AcpPromptCompleteParams{}},
	{Name: "env.hitl_request", MessageKind: Request, Direction: ConnectorToControlPlane, Params: &HitlRequest{}, Result: &HitlResult{}},
	{Name: "env.exec", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &ExecParams{}, Result: &ExecResult{}},
	{Name: "env.registerGitAuth", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &RegisterGitAuthParams{}, Result: &RegisterGitAuthResult{}},
	{Name: "env.checkout", MessageKind: Notification, Direction: ControlPlaneToConnector, Params: &CheckoutParams{}},
	{Name: "env.launch_acp_agent", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &LaunchAcpAgentParams{}, Result: &LaunchAcpAgentResult{}},
	{Name: "env.acp_prompt", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &AcpPromptParams{}, Result: &AcpPromptResult{}},
	{Name: "env.terminate", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &TerminateParams{}, Result: &TerminateResult{}},
	{Name: "env.git_diff_summary", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &GitDiffSummaryParams{}, Result: &GitDiffSummaryResult{}},
	{Name: "env.git_file_diff", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &GitFileDiffParams{}, Result: &GitFileDiffResult{}},
	{Name: "env.read_file_slice", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &ReadFileSliceParams{}, Result: &ReadFileSliceResult{}},
	{Name: "env.git_push", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &GitPushParams{}, Result: &GitPushResult{}},
	{Name: "env.git_set_remote", MessageKind: Request, Direction: ControlPlaneToConnector, Params: &GitSetRemoteParams{}, Result: &GitSetRemoteResult{}},
}
