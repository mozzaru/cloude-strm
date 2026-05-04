import uuid

alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

def base58_to_uuid(b58):
    n = 0
    for char in b58:
        n = n * 58 + alphabet.index(char)

    hex_str = hex(n)[2:].zfill(32)
    return str(uuid.UUID(hex_str))

print(base58_to_uuid("XaYior9t8NtPvbC5LsC9wV"))
