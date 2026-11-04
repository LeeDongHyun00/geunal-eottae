defmodule GatewayWeb.DeviceTransport do
  @moduledoc """
  `GatewayWeb.DeviceSocket` with a cap on the frames a connection may send (gateway.md §7). A device sends only a
  join and a heartbeat every 30 seconds (protocol.md §7), yet each frame it sends is decoded and answered, so without a
  cap one connection could keep a node busy for as long as it stays up. Past `:max_frames_per_minute` in a minute the
  connection is closed; a device that misbehaves reconnects, and the connection limit then holds it back.

  Phoenix's socket defines its transport callbacks without making them overridable, hence a module around it rather
  than a callback in it.
  """
  @behaviour Phoenix.Socket.Transport

  require Logger

  @socket GatewayWeb.DeviceSocket
  @window :timer.minutes(1)

  @impl true
  defdelegate child_spec(opts), to: @socket

  @impl true
  defdelegate drainer_spec(opts), to: @socket

  @impl true
  defdelegate connect(config), to: @socket

  @impl true
  def init(state) do
    limit = Application.fetch_env!(:gateway, :max_frames_per_minute)
    wrap(@socket.init(state), %{limit: limit, window: window(), frames: 0})
  end

  @impl true
  def handle_in(message, {limits, inner}) do
    limits = count(limits)

    if limits.frames > limits.limit do
      Logger.info("closing a device connection that sent more than #{limits.limit} frames in a minute")
      {:stop, {:shutdown, :too_many_frames}, {limits, inner}}
    else
      wrap(@socket.handle_in(message, inner), limits)
    end
  end

  @impl true
  def handle_info(message, {limits, inner}), do: wrap(@socket.handle_info(message, inner), limits)

  @impl true
  def terminate(reason, {_limits, inner}), do: @socket.terminate(reason, inner)

  defp count(%{window: window} = limits) do
    case window() do
      ^window -> %{limits | frames: limits.frames + 1}
      current -> %{limits | window: current, frames: 1}
    end
  end

  defp window, do: div(System.monotonic_time(:millisecond), @window)

  # Every transport callback returns its state last.
  defp wrap(result, limits),
    do: put_elem(result, tuple_size(result) - 1, {limits, elem(result, tuple_size(result) - 1)})
end
