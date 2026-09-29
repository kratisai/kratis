export interface UserServiceInterface {
    getUser(id: string): string;
}

export abstract class BaseController {
    handleRequest(path: string): string {
        return `Handled: ${path}`;
    }
}

export function logExecution(message: string): void {
    console.log(message);
}
