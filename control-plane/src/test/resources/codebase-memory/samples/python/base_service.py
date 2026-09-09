from abc import ABC, abstractmethod
import logging

logger = logging.getLogger(__name__)

def log_execution(func):
    """A custom decorator to log method execution."""
    def wrapper(*args, **kwargs):
        logger.info(f"Executing {func.__name__}")
        return func(*args, **kwargs)
    return wrapper

class BaseService(ABC):
    @abstractmethod
    def get_item(self, item_id: str) -> str:
        pass
    
    @log_execution
    def handle_request(self, path: str) -> str:
        return f"Handled: {path}"
