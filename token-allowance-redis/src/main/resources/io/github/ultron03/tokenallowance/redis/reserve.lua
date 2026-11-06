-- Reserves an estimate on every budget, or on none if one of them has no room for it.
-- KEYS[1]          reservation key
-- KEYS[2..n]       budget keys
-- ARGV[1], ARGV[2] estimate, as tokens and micros
-- ARGV[3]          how long the reservation key lives, in seconds
-- ARGV[3*i - 2..]  for KEYS[i]: max tokens, max micros (-1 for no cap) and expiry in epoch seconds
-- Returns 0 when reserved, otherwise i - 1 for the first KEYS[i] that has no room.
-- Lua compares as doubles, which is exact up to 2^53 tokens or micros.
-- Field names match the constants in RedisKeys.

local tokens = tonumber(ARGV[1])
local micros = tonumber(ARGV[2])

local function exceeds(total, cap)
	return cap >= 0 and total > cap
end

-- Check every budget before writing any, so a denial leaves all of them unchanged.
for i = 2, #KEYS do
	local spend = redis.call('HMGET', KEYS[i],
		'committed_tokens', 'reserved_tokens', 'committed_micros', 'reserved_micros')
	local tokensAfter = (tonumber(spend[1]) or 0) + (tonumber(spend[2]) or 0) + tokens
	local microsAfter = (tonumber(spend[3]) or 0) + (tonumber(spend[4]) or 0) + micros
	local arg = 3 * i - 2
	if exceeds(tokensAfter, tonumber(ARGV[arg])) or exceeds(microsAfter, tonumber(ARGV[arg + 1])) then
		return i - 1
	end
end

for i = 2, #KEYS do
	redis.call('HINCRBY', KEYS[i], 'reserved_tokens', ARGV[1])
	redis.call('HINCRBY', KEYS[i], 'reserved_micros', ARGV[2])
	redis.call('EXPIREAT', KEYS[i], ARGV[3 * i])
end
redis.call('SET', KEYS[1], '1', 'EX', ARGV[3])
return 0
