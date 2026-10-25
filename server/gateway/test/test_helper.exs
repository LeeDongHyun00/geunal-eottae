# Redix connects in the background, so give it five seconds to reach Redis before the tests that need it run.
redis_ready? =
  Enum.reduce_while(1..50, false, fn _, _ ->
    if Redix.command(Gateway.Redis, ["PING"]) == {:ok, "PONG"} do
      {:halt, true}
    else
      Process.sleep(100)
      {:cont, false}
    end
  end)

redis_ready? || raise "Redis at #{Application.fetch_env!(:gateway, :redis_url)} did not answer PING within five seconds"

ExUnit.start()
