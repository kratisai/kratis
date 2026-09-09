import {UserServiceInterface} from "./UserServiceInterface";

abstract class BaseController {
    handleRequest(path: string): string {
        return `Handled: ${path}`;
    }
}

export class UserController extends BaseController implements UserServiceInterface {
    getUser(id: string): string {
        return `User-${id}`;
    }

    listUsers(): string[] {
        return ['user1', 'user2'];
    }

    processRequest(): string {
        return this.handleRequest('/users');
    }
}
