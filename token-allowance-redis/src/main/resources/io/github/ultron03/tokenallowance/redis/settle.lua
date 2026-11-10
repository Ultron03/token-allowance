-- Settles a reservation: takes its estimate off every budget that still holds it and adds what was charged.
-- KEYS[1..n]       budget keys of the reservation
-- KEYS[n+1..2n]    their sets of open reservations, in the same order
-- ARGV[1]          the reservation's member in those sets, "id:tokens:micros"
-- ARGV[2], ARGV[3] estimate to take off, as negative tokens and micros
-- ARGV[4], ARGV[5] amount to charge, as tokens and micros ("0", "0" for a release)
-- ARGV[5 + i]      expiry of KEYS[i] in epoch seconds
-- Returns the committed tokens and micros of each budget key after the charge, in the order of
-- KEYS[1..n], as one flat list; an empty list when no budget holds the reservation any more,
-- because it was already settled or timed out everywhere.
-- A budget that already dropped the timed-out reservation is still charged, without taking the estimate off twice.
-- Field names match the constants in RedisKeys.

local n = #KEYS / 2
local held = {}
local anyHeld = false
for i = 1, n do
	held[i] = redis.call('ZREM', KEYS[n + i], ARGV[1]) == 1
	anyHeld = anyHeld or held[i]
end
if not anyHeld then
	return {}
end

local committed = {}
for i = 1, n do
	if held[i] then
		redis.call('HINCRBY', KEYS[i], 'reserved_tokens', ARGV[2])
		redis.call('HINCRBY', KEYS[i], 'reserved_micros', ARGV[3])
	end
	local tokens = redis.call('HINCRBY', KEYS[i], 'committed_tokens', ARGV[4])
	local micros = redis.call('HINCRBY', KEYS[i], 'committed_micros', ARGV[5])
	redis.call('EXPIREAT', KEYS[i], ARGV[5 + i])
	table.insert(committed, tokens)
	table.insert(committed, micros)
end
return committed
