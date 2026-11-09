-- Settles a reservation: removes its estimate from every budget and adds what was charged.
-- KEYS[1]          reservation key
-- KEYS[2..n]       budget keys of the reservation
-- ARGV[1], ARGV[2] estimate to remove, as negative tokens and micros
-- ARGV[3], ARGV[4] amount to charge, as tokens and micros ("0", "0" for a release)
-- ARGV[5..]        expiry of each budget key in epoch seconds, in the order of KEYS[2..n]
-- Returns the committed tokens and micros of each budget key after the charge, in the order of
-- KEYS[2..n], as one flat list; an empty list when it was already settled or has expired.
-- Field names match the constants in RedisKeys.

if redis.call('DEL', KEYS[1]) == 0 then
	return {}
end

local committed = {}
for i = 2, #KEYS do
	redis.call('HINCRBY', KEYS[i], 'reserved_tokens', ARGV[1])
	redis.call('HINCRBY', KEYS[i], 'reserved_micros', ARGV[2])
	local tokens = redis.call('HINCRBY', KEYS[i], 'committed_tokens', ARGV[3])
	local micros = redis.call('HINCRBY', KEYS[i], 'committed_micros', ARGV[4])
	redis.call('EXPIREAT', KEYS[i], ARGV[i + 3])
	table.insert(committed, tokens)
	table.insert(committed, micros)
end
return committed
