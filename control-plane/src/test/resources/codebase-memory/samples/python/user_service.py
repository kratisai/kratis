from .base_service import BaseService, log_execution

class UserService(BaseService):
    def get_item(self, item_id: str) -> str:
        return f"User-{item_id}"
    
    @log_execution
    def get_all_users(self) -> list:
        users = ["user1", "user2"]
        # Cross-file call to demonstrate usage/dependency
        self.handle_request("/api/users")
        return users
