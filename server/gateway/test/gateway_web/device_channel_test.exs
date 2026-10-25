defmodule GatewayWeb.DeviceChannelTest do
  use ExUnit.Case, async: true

  import Phoenix.ChannelTest

  alias GatewayTest.Keys
  alias GatewayWeb.DeviceSocket

  @endpoint GatewayWeb.Endpoint
  @moduletag :capture_log

  @vectors "../../../../core/vectors/realtime_events.json" |> Path.expand(__DIR__) |> File.read!() |> Jason.decode!()

  setup do
    device_id = Keys.uuid()
    {:ok, socket} = connect(DeviceSocket, %{"token" => Keys.token(Keys.main(), %{"did" => device_id})})
    # The test process stands in for the socket's transport, which closes the connection on this broadcast.
    @endpoint.subscribe(socket.id)
    %{socket: socket, device_id: device_id}
  end

  defp publish(device_id, message) do
    {:ok, _receivers} = Redix.command(Gateway.Redis, ["PUBLISH", "d:" <> device_id, message])
    :ok
  end

  defp subscribers(device_id) do
    {:ok, [_channel, count]} = Redix.command(Gateway.Redis, ["PUBSUB", "NUMSUB", "d:" <> device_id])
    count
  end

  defp eventually(fun, attempts \\ 50) do
    cond do
      fun.() -> true
      attempts == 0 -> false
      true -> Process.sleep(20) && eventually(fun, attempts - 1)
    end
  end

  test "lets a device join its own topic only", %{socket: socket, device_id: device_id} do
    assert {:error, %{reason: "forbidden"}} = subscribe_and_join(socket, "device:" <> Keys.uuid())
    assert {:ok, _reply, _socket} = subscribe_and_join(socket, "device:" <> device_id)
  end

  test "has Redis deliver by the time the join is answered", %{socket: socket, device_id: device_id} do
    assert subscribers(device_id) == 0
    {:ok, _reply, _socket} = subscribe_and_join(socket, "device:" <> device_id)
    assert subscribers(device_id) == 1
  end

  for %{"name" => name} = case <- @vectors["cases"] do
    @case case
    test "pushes the vector '#{name}' as the contract says", %{socket: socket, device_id: device_id} do
      {:ok, _reply, _socket} = subscribe_and_join(socket, "device:" <> device_id)

      publish(device_id, @case["redis_message"])

      case @case do
        %{"dropped" => true} ->
          publish(device_id, ~s({"t":"welcome"}))
          # The next event arrives in order, so the dropped one would have shown up before it.
          assert_push "welcome", %{}
          refute_received %Phoenix.Socket.Message{}

        %{"event" => "revoke", "payload" => payload} ->
          assert_push "revoke", ^payload
          assert_receive %Phoenix.Socket.Broadcast{event: "disconnect"}

        %{"event" => event, "payload" => payload} ->
          assert_push ^event, ^payload
          refute_received %Phoenix.Socket.Broadcast{event: "disconnect"}
      end
    end
  end

  test "pushes to every connection of the device", %{socket: socket, device_id: device_id} do
    {:ok, _reply, _first} = subscribe_and_join(socket, "device:" <> device_id)
    {:ok, _reply, _second} = subscribe_and_join(socket, "device:" <> device_id)

    publish(device_id, ~s({"t":"membership","g":"g1"}))

    assert_push "membership", %{"g" => "g1"}
    assert_push "membership", %{"g" => "g1"}
  end

  test "stops listening on Redis once the device's last channel is gone", %{socket: socket, device_id: device_id} do
    {:ok, _reply, first} = subscribe_and_join(socket, "device:" <> device_id)
    {:ok, _reply, second} = subscribe_and_join(socket, "device:" <> device_id)

    Process.unlink(first.channel_pid)
    close(first)
    assert subscribers(device_id) == 1

    Process.unlink(second.channel_pid)
    close(second)
    assert eventually(fn -> subscribers(device_id) == 0 end)

    publish(device_id, ~s({"t":"welcome"}))
    refute_receive %Phoenix.Socket.Message{}, 100
  end

  test "answers anything the device sends with an error", %{socket: socket, device_id: device_id} do
    {:ok, _reply, socket} = subscribe_and_join(socket, "device:" <> device_id)

    ref = push(socket, "entry", %{"g" => "g1"})

    assert_reply ref, :error, %{reason: "not_allowed"}
  end

  test "closes the connection of a device whose channel mailbox overflows", %{socket: socket, device_id: device_id} do
    {:ok, _reply, socket} = subscribe_and_join(socket, "device:" <> device_id)
    limit = Application.fetch_env!(:gateway, :slow_device_mailbox)

    # A stalled channel: the router keeps handing it messages until the limit, then closes the connection instead.
    :sys.suspend(socket.channel_pid)
    commands = for _ <- 1..(limit + 2), do: ["PUBLISH", "d:" <> device_id, ~s({"t":"welcome"})]
    {:ok, _} = Redix.pipeline(Gateway.Redis, commands)

    assert_receive %Phoenix.Socket.Broadcast{event: "disconnect"}, 5_000
    :sys.resume(socket.channel_pid)
  end

  test "closes a connection that has lived its maximum age", %{socket: socket, device_id: device_id} do
    max_age = Application.fetch_env!(:gateway, :max_connection_age)
    socket = Phoenix.Socket.assign(socket, :connected_at, System.monotonic_time(:millisecond) - max_age + 100)

    {:ok, _reply, _socket} = subscribe_and_join(socket, "device:" <> device_id)

    refute_receive %Phoenix.Socket.Broadcast{event: "disconnect"}, 50
    assert_receive %Phoenix.Socket.Broadcast{event: "disconnect"}, 1_000
  end
end
