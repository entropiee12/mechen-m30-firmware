"""Status notification state and timers from mainmenu.ap."""

from __future__ import annotations

from dataclasses import dataclass, field


STATUS_ZERO_NOTIFICATION = 0x82
STATUS_TWO_NOTIFICATION = 0xF2
STATUS_NOTIFICATION_TICKS = 0x78
STATUS_TWO_REFRESH_TICKS = 8
STATUS_LEVEL_MAX = 6
STATUS_CONFIRM_EVENT = 0x102

_STATUS_PRESENTATIONS = {
    STATUS_ZERO_NOTIFICATION: ((0x1005, 0x1006, 0x2001), STATUS_CONFIRM_EVENT, 0x8015),
    STATUS_TWO_NOTIFICATION: ((0x1008, 0x1007, 0x2000), None, 0x8014),
}


@dataclass(frozen=True)
class StatusNotification:
    """One result from the mainmenu status updater."""

    event: int | None
    refresh_key_status: bool
    state_changed: bool
    status: int
    level: int


@dataclass(frozen=True)
class StatusMessagePresentation:
    """Verified resource and return-code envelope for a status message."""

    resource_ids: tuple[int, ...]
    confirm_event: int | None
    ap_return_code: int


def status_message_presentation(message: int) -> StatusMessagePresentation:
    """Return the mainmenu presentation contract for status 0 or status 2."""
    try:
        resource_ids, confirm_event, ap_return_code = _STATUS_PRESENTATIONS[message]
    except KeyError as error:
        raise ValueError(f"unsupported status message 0x{message:04x}") from error

    return StatusMessagePresentation(resource_ids, confirm_event, ap_return_code)


@dataclass
class StatusMessageSession:
    """Track the confirmed event needed for a status handler to return."""

    presentation: StatusMessagePresentation
    complete: bool = field(init=False)

    def __post_init__(self) -> None:
        self.complete = self.presentation.confirm_event is None

    @property
    def ap_return_code(self) -> int | None:
        if not self.complete:
            return None

        return self.presentation.ap_return_code

    def poll(self, event: int | None, available: bool) -> bool:
        """Apply one event-service result; unavailable event data is ignored."""
        if not isinstance(available, bool):
            raise ValueError("event availability must be a boolean")

        if available and (event is None or not 0 <= event <= 0xFFFFFFFF):
            raise ValueError("an available event must fit an unsigned 32-bit word")

        if self.complete or not available:
            return self.complete

        if event == self.presentation.confirm_event:
            self.complete = True

        return self.complete


def begin_status_message(message: int) -> StatusMessageSession:
    """Start the observed mainmenu handler for one status notification."""
    return StatusMessageSession(status_message_presentation(message))


@dataclass
class StatusNotifier:
    """Model mainmenu status normalization and notification timers.

    ``tick`` corresponds to one invocation of FUN_48b949f4. The status-change
    helper's verified writes and timer resets are included; UI drawing, message
    consumers, and the hardware meaning of status values remain outside scope.
    """

    status: int = 0
    level: int = 0
    _status_zero_ticks: int = 0
    _status_two_ticks: int = 0

    def tick(self, sampled_status: int, sampled_level: int) -> StatusNotification:
        if sampled_status not in (0, 1, 2, 4):
            raise ValueError("sampled status must be one of the observed values 0, 1, 2, or 4")

        if not 0 <= sampled_level <= 0xFF:
            raise ValueError("sampled level must fit one byte")

        status = sampled_status
        level = min(sampled_level, STATUS_LEVEL_MAX)
        if status == 4:
            status = 0
            level = STATUS_LEVEL_MAX

        if self.status == 0 and status == 0 and self.level < level:
            level = self.level

        if status != self.status or level != self.level:
            self._apply_state_change(status, level)
            self.status = status
            self.level = level

            return StatusNotification(None, False, True, status, level)

        return self._tick_unchanged_state(sampled_level)

    def _tick_unchanged_state(self, sampled_level: int) -> StatusNotification:
        if self.status == 0 and self.level == 0:
            self._status_zero_ticks += 1

            if self._status_zero_ticks >= STATUS_NOTIFICATION_TICKS:
                self._status_zero_ticks = 0

                return StatusNotification(
                    STATUS_ZERO_NOTIFICATION,
                    False,
                    False,
                    self.status,
                    self.level,
                )

            return StatusNotification(None, False, False, self.status, self.level)

        self._status_zero_ticks = 0

        if self.status != 2:
            return StatusNotification(None, False, False, self.status, self.level)

        self._status_two_ticks += 1
        refresh_key_status = (
            sampled_level < STATUS_LEVEL_MAX
            and self._status_two_ticks == STATUS_TWO_REFRESH_TICKS
        )
        if refresh_key_status:
            self._status_two_ticks = 0

        if self._status_two_ticks >= STATUS_NOTIFICATION_TICKS:
            self._status_two_ticks = 0

            return StatusNotification(
                STATUS_TWO_NOTIFICATION,
                refresh_key_status,
                False,
                self.status,
                self.level,
            )

        return StatusNotification(None, refresh_key_status, False, self.status, self.level)

    def _apply_state_change(self, status: int, level: int) -> None:
        if status == 0:
            if level == 0 and (self.status != 0 or self.level != 0):
                self._status_zero_ticks = 0

        elif status == 2 and self.status != 2:
            self._status_two_ticks = 0
