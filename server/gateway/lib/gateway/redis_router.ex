defmodule Gateway.RedisRouter do
  @moduledoc """
  The node's one `Redix.PubSub` connection (gateway.md §5). A device channel that joins asks to be routed; the router
  SUBSCRIBEs `d:{device_id}` the first time a device appears on this node, forwards every message on it to the
  channels registered under that device in `Gateway.DeviceRegistry`, and UNSUBSCRIBEs when the last of them is gone.

  Forwarding looks at the channel's mailbox first: past `slow_device_mailbox` messages the device is not keeping up,
  and its connection is closed instead of growing the backlog (it reconnects and catches up through the API).
  """
  use GenServer

  require Logger

  @doc """
  Options: `:name`, `:redis_url` (default: the `:redis_url` application env), `:registry` (default
  `Gateway.DeviceRegistry`), `:pubsub` (the Phoenix PubSub that reaches sockets, default `Gateway.PubSub`),
  `:slow_device_mailbox` (default: the `:slow_device_mailbox` application env).
  """
  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc """
  Routes `d:{device_id}` to the calling channel process until it exits. Returns once Redis has confirmed the
  subscription, so a message published after the join cannot slip past it; `{:error, :unavailable}` when Redis does
  not confirm within `timeout`.
  """
  @spec subscribe(String.t(), String.t(), atom() | pid(), timeout()) :: {:ok, pid()} | {:error, :unavailable}
  def subscribe(device_id, socket_id, router \\ __MODULE__, timeout \\ 5_000) do
    registry = GenServer.call(router, :registry)
    {:ok, _owner} = Registry.register(registry, device_id, %{socket_id: socket_id})
    GenServer.call(router, {:subscribe, device_id, self()}, timeout)
  catch
    :exit, _ -> {:error, :unavailable}
  end

  @doc "Whether the pub/sub connection currently reaches Redis."
  @spec connected?(atom() | pid()) :: boolean()
  def connected?(router \\ __MODULE__) do
    Redix.PubSub.ping(GenServer.call(router, :connection), 1_000) == :ok
  catch
    :exit, _ -> false
  end

  ## Server

  @impl true
  def init(opts) do
    url = Keyword.get_lazy(opts, :redis_url, fn -> Application.fetch_env!(:gateway, :redis_url) end)
    # Connects in the background and reconnects on its own, re-subscribing everything it was subscribed to. The client
    # id lets a test (and an operator) kill exactly this connection on the Redis side.
    {:ok, conn} = Redix.PubSub.start_link(url, fetch_client_id_on_connect: true)

    {:ok,
     %{
       conn: conn,
       registry: Keyword.get(opts, :registry, Gateway.DeviceRegistry),
       pubsub: Keyword.get(opts, :pubsub, Gateway.PubSub),
       limit:
         Keyword.get_lazy(opts, :slow_device_mailbox, fn -> Application.fetch_env!(:gateway, :slow_device_mailbox) end),
       # device_id => %{pids: MapSet, confirmed?: boolean, waiting: [from]}
       devices: %{},
       monitors: %{}
     }}
  end

  @impl true
  def handle_call(:registry, _from, state), do: {:reply, state.registry, state}
  def handle_call(:connection, _from, state), do: {:reply, state.conn, state}

  def handle_call({:subscribe, device_id, pid}, from, state) do
    ref = Process.monitor(pid)
    state = put_in(state.monitors[ref], {pid, device_id})

    case state.devices do
      %{^device_id => %{confirmed?: true} = device} ->
        {:reply, {:ok, self()}, put_in(state.devices[device_id], add_pid(device, pid))}

      %{^device_id => device} ->
        device = %{add_pid(device, pid) | waiting: [from | device.waiting]}
        {:noreply, put_in(state.devices[device_id], device)}

      _ ->
        {:ok, _ref} = Redix.PubSub.subscribe(state.conn, channel(device_id), self())
        device = %{pids: MapSet.new([pid]), confirmed?: false, waiting: [from]}
        {:noreply, put_in(state.devices[device_id], device)}
    end
  end

  @impl true
  def handle_info({:redix_pubsub, _conn, _ref, :subscribed, %{channel: "d:" <> device_id}}, state) do
    case state.devices do
      %{^device_id => device} ->
        Enum.each(device.waiting, &GenServer.reply(&1, {:ok, self()}))
        {:noreply, put_in(state.devices[device_id], %{device | confirmed?: true, waiting: []})}

      _ ->
        {:noreply, state}
    end
  end

  def handle_info({:redix_pubsub, _conn, _ref, :message, %{channel: "d:" <> device_id, payload: payload}}, state) do
    Registry.dispatch(state.registry, device_id, fn entries ->
      Enum.each(entries, fn {pid, %{socket_id: socket_id}} -> forward(pid, socket_id, payload, state) end)
    end)

    {:noreply, state}
  end

  def handle_info({:redix_pubsub, _conn, _ref, :disconnected, %{error: error}}, state) do
    # Whatever the API publishes until Redix reconnects is lost here, and the device would not know: close its
    # connections, so it comes back with backoff and catches up through the API (protocol.md §7), which also ends
    # the channels and, with them, the subscriptions. Joins still waiting for a confirmation fail the same way.
    Logger.warning(
      "Redis pub/sub disconnected, closing #{map_size(state.devices)} devices: #{Exception.message(error)}"
    )

    devices = Map.new(state.devices, fn {device_id, device} -> {device_id, close_device(device_id, device, state)} end)
    {:noreply, %{state | devices: devices}}
  end

  def handle_info({:redix_pubsub, _conn, _ref, _other, _properties}, state), do: {:noreply, state}

  def handle_info({:DOWN, ref, :process, pid, _reason}, state) do
    {monitored, monitors} = Map.pop(state.monitors, ref)
    forget(monitored, pid, %{state | monitors: monitors})
  end

  # A channel is gone; the device's Redis subscription goes with its last channel.
  defp forget(nil, _pid, state), do: {:noreply, state}

  defp forget({_pid, device_id}, pid, state) do
    case state.devices do
      %{^device_id => device} ->
        pids = MapSet.delete(device.pids, pid)

        if MapSet.size(pids) == 0 do
          :ok = Redix.PubSub.unsubscribe(state.conn, channel(device_id), self())
          {:noreply, %{state | devices: Map.delete(state.devices, device_id)}}
        else
          {:noreply, put_in(state.devices[device_id], %{device | pids: pids})}
        end

      _ ->
        {:noreply, state}
    end
  end

  defp close_device(device_id, device, state) do
    Enum.each(device.waiting, &GenServer.reply(&1, {:error, :unavailable}))

    Registry.dispatch(state.registry, device_id, fn entries ->
      Enum.each(entries, fn {_pid, %{socket_id: socket_id}} -> close(socket_id, state) end)
    end)

    %{device | confirmed?: false, waiting: []}
  end

  # The socket process closes the connection on this broadcast, and the channels end with it.
  defp close(socket_id, state) do
    broadcast = %Phoenix.Socket.Broadcast{topic: socket_id, event: "disconnect", payload: %{}}
    Phoenix.PubSub.broadcast(state.pubsub, socket_id, broadcast)
  end

  defp add_pid(device, pid), do: %{device | pids: MapSet.put(device.pids, pid)}

  defp channel(device_id), do: "d:" <> device_id

  defp forward(pid, socket_id, payload, state) do
    case Process.info(pid, :message_queue_len) do
      {:message_queue_len, len} when len > state.limit ->
        Logger.warning("closing a slow device connection: #{len} messages waiting")
        close(socket_id, state)

      {:message_queue_len, _len} ->
        send(pid, {:redis_event, payload})

      nil ->
        :ok
    end
  end
end
