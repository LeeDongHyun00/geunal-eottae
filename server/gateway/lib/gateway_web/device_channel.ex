defmodule GatewayWeb.DeviceChannel do
  @moduledoc """
  `device:{device_id}`, the one topic a device may join, and only for its own id (gateway.md §4). Everything the API
  publishes on Redis `d:{device_id}` is pushed as the event its `t` field names (protocol.md §7); `revoke` also closes
  the connection. The device sends nothing here but heartbeats, which the socket answers.
  """
  use Phoenix.Channel

  require Logger

  @events ~w(entry state membership welcome revoke)

  @impl true
  def join("device:" <> device_id, _params, %{assigns: %{device_id: device_id}} = socket) do
    case Gateway.RedisRouter.subscribe(device_id, socket.id) do
      {:ok, router} ->
        # If the router goes, so does the subscription: end the channel so the device reconnects and catches up.
        Process.monitor(router)
        Process.send_after(self(), :max_age, remaining_lifetime(socket))
        {:ok, socket}

      {:error, :unavailable} ->
        {:error, %{reason: "unavailable"}}
    end
  end

  def join("device:" <> _other, _params, _socket), do: {:error, %{reason: "forbidden"}}

  @impl true
  def handle_in(_event, _payload, socket), do: {:reply, {:error, %{reason: "not_allowed"}}, socket}

  @impl true
  def handle_info({:redis_event, payload}, socket) do
    case Jason.decode(payload) do
      {:ok, %{"t" => type} = event} when type in @events ->
        push(socket, type, Map.delete(event, "t"))
        if type == "revoke", do: disconnect(socket)

      {:ok, _} ->
        Logger.warning("dropped a Redis event without a known type")

      {:error, _} ->
        Logger.warning("dropped a Redis event that is not JSON")
    end

    {:noreply, socket}
  end

  def handle_info(:max_age, socket) do
    disconnect(socket)
    {:noreply, socket}
  end

  def handle_info({:DOWN, _ref, :process, _router, _reason}, socket) do
    {:stop, {:shutdown, :router_down}, socket}
  end

  defp remaining_lifetime(socket) do
    max_age = Application.fetch_env!(:gateway, :max_connection_age)
    max(socket.assigns.connected_at + max_age - System.monotonic_time(:millisecond), 0)
  end

  # Pushes already sent sit ahead of this in the transport's mailbox, so a `revoke` reaches the device before the
  # close.
  defp disconnect(socket), do: socket.endpoint.broadcast(socket.id, "disconnect", %{})
end
