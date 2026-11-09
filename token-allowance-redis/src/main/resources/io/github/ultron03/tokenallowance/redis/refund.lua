-- Takes an amount off a budget's committed spend, never below zero; reservations stay.
-- KEYS[1]          budget key
-- ARGV[1], ARGV[2] tokens and micros to take off
-- A missing key is left missing, so no key without an expiry is created.
-- Field names match the constants in RedisKeys.

if redis.call('EXISTS', KEYS[1]) == 0 then
	return 0
end

local function takeOff(field, wanted)
	local committed = tonumber(redis.call('HGET', KEYS[1], field)) or 0
	local amount = math.min(committed, tonumber(wanted))
	if amount > 0 then
		redis.call('HINCRBY', KEYS[1], field, string.format('%d', -amount))
	end
end

takeOff('committed_tokens', ARGV[1])
takeOff('committed_micros', ARGV[2])
return 1
