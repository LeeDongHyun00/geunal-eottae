defmodule Gateway.RedisRouterTest do
  use ExUnit.Case, async: true

  alias Gateway.RedisRouter

  @moduletag :capture_log

  # A router of its own, with its own registry and a tiny mailbox limit, so this does not touch the gateway's.
  setup context do
    registry = :"registry_#{context.test}"
    router = :"router_#{context.test}"
    start_supervised!({Registry, keys: :duplicate, name: registry})
    start_supervised!({RedisRouter, name: router, registry: registry, slow_device_mailbox: 3, pubsub: Gateway.PubSub})
    %{router: router, device_id: GatewayTest.Keys.uuid()}
  end

  defp subscribers(device_id) do
    {:ok, [_channel, count]} = Redix.command(Gateway.Redis, ["PUBSUB", "NUMSUB", "d:" <> device_id])
    count
  end

  defp channel(router, device_id, socket_id) do
    parent = self()

    spawn_link(fn ->
      send(parent, {:subscribed, RedisRouter.subscribe(device_id, socket_id, router)})

      receive do
        :stop -> :ok
      end
    end)
  end

  test "subscribes once per device and forwards to every registered channel", %{router: router, device_id: device_id} do
    assert {:ok, pid} = RedisRouter.subscribe(device_id, "device_socket:" <> device_id, router)
    assert pid == Process.whereis(router)
    assert subscribers(device_id) == 1

    other = channel(router, device_id, "device_socket:" <> device_id)
    assert_receive {:subscribed, {:ok, _}}
    assert subscribers(device_id) == 1

    {:ok, 1} = Redix.command(Gateway.Redis, ["PUBLISH", "d:" <> device_id, "hello"])
    assert_receive {:redis_event, "hello"}
    assert {:message_queue_len, 1} = Process.info(other, :message_queue_len)
  end

  test "unsubscribes when the last channel of the device exits", %{router: router, device_id: device_id} do
    first = channel(router, device_id, "s")
    second = channel(router, device_id, "s")
    assert_receive {:subscribed, {:ok, _}}
    assert_receive {:subscribed, {:ok, _}}

    send(first, :stop)
    Process.sleep(50)
    assert subscribers(device_id) == 1

    send(second, :stop)
    assert Enum.any?(1..50, fn _ -> subscribers(device_id) == 0 || (Process.sleep(20) && false) end)
  end

  test "closes the socket of a channel that stopped reading instead of piling up", %{
    router: router,
    device_id: device_id
  } do
    socket_id = "device_socket:" <> device_id
    Phoenix.PubSub.subscribe(Gateway.PubSub, socket_id)
    slow = channel(router, device_id, socket_id)
    assert_receive {:subscribed, {:ok, _}}

    # The stand-in channel never reads its events, so they pile up in its mailbox.
    commands = for _ <- 1..6, do: ["PUBLISH", "d:" <> device_id, "x"]
    {:ok, _} = Redix.pipeline(Gateway.Redis, commands)

    assert_receive %Phoenix.Socket.Broadcast{topic: ^socket_id, event: "disconnect"}
    # Nothing past the limit was handed to the channel.
    assert {:message_queue_len, 4} = Process.info(slow, :message_queue_len)
  end

  test "reports a join unavailable while Redis does not confirm", context do
    registry = :"registry_down_#{context.test}"
    router = :"router_down_#{context.test}"
    start_supervised!(Supervisor.child_spec({Registry, keys: :duplicate, name: registry}, id: registry))
    # Nothing listens on port 1.
    spec = {RedisRouter, name: router, registry: registry, redis_url: "redis://127.0.0.1:1"}
    start_supervised!(Supervisor.child_spec(spec, id: router))

    refute RedisRouter.connected?(router)
    assert RedisRouter.subscribe(context.device_id, "s", router, 200) == {:error, :unavailable}
  end
end
