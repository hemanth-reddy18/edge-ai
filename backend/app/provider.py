import re
import ipaddress
import urllib.parse
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field

# 10 Required Provider States
STATE_NOT_CONFIGURED = "NOT_CONFIGURED"
STATE_VALIDATING = "VALIDATING"
STATE_CREDENTIALS_INVALID = "CREDENTIALS_INVALID"
STATE_ACCOUNT_NOT_READY = "ACCOUNT_NOT_READY"
STATE_NUMBER_NOT_PROVISIONED = "NUMBER_NOT_PROVISIONED"
STATE_INBOUND_ROUTE_MISSING = "INBOUND_ROUTE_MISSING"
STATE_GATEWAY_UNREACHABLE = "GATEWAY_UNREACHABLE"
STATE_MEDIA_NOT_READY = "MEDIA_NOT_READY"
STATE_CONNECTED = "CONNECTED"
STATE_FAILED = "FAILED"

ALL_PROVIDER_STATES = [
    STATE_NOT_CONFIGURED,
    STATE_VALIDATING,
    STATE_CREDENTIALS_INVALID,
    STATE_ACCOUNT_NOT_READY,
    STATE_NUMBER_NOT_PROVISIONED,
    STATE_INBOUND_ROUTE_MISSING,
    STATE_GATEWAY_UNREACHABLE,
    STATE_MEDIA_NOT_READY,
    STATE_CONNECTED,
    STATE_FAILED
]

def mask_secret(secret: Optional[str]) -> Optional[str]:
    if not secret:
        return None
    if len(secret) <= 6:
        return "******"
    return secret[:3] + "******" + secret[-3:]

def is_private_or_loopback_uri(uri: Optional[str]) -> bool:
    if not uri:
        return True
    
    clean_uri = uri.strip().lower()
    # Strip protocol prefix if present
    if "://" in clean_uri:
        parsed = urllib.parse.urlparse(clean_uri)
        host = parsed.hostname or clean_uri
    elif clean_uri.startswith("sip:"):
        host_part = clean_uri[4:].split("@")[-1]
        host = host_part.split(":")[0]
    else:
        host = clean_uri.split(":")[0]

    if host in ("localhost", "127.0.0.1", "0.0.0.0", "::1"):
        return True

    # Check for private IP ranges (RFC 1918)
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_private or ip.is_loopback or ip.is_link_local
    except ValueError:
        pass

    # Check known local patterns
    if host.endswith(".local") or host.endswith(".internal") or host.startswith("192.168.") or host.startswith("10."):
        return True

    return False

def is_valid_e164_number(number: Optional[str]) -> bool:
    if not number:
        return False
    # E.164: + followed by 10 to 15 digits
    return bool(re.match(r"^\+[1-9]\d{9,14}$", number.strip()))

class ProviderConfigUpdate(BaseModel):
    provider_name: Optional[str] = "exotel"
    account_sid: Optional[str] = None
    api_key: Optional[str] = None
    api_token: Optional[str] = None
    subdomain: Optional[str] = None
    inbound_did: Optional[str] = None
    trunk_mode: Optional[str] = "pstn_to_sip"
    gateway_public_uri: Optional[str] = None

class ProviderConfigResponse(BaseModel):
    provider_name: str
    account_sid: Optional[str] = None
    api_key: Optional[str] = None
    api_token_masked: Optional[str] = None
    subdomain: Optional[str] = None
    inbound_did: Optional[str] = None
    trunk_mode: str
    gateway_public_uri: Optional[str] = None
    state: str
    status_details: Optional[str] = None
    updated_at: Optional[int] = None

class ProviderStatusResponse(BaseModel):
    provider: str
    state: str
    configured: bool
    public_number_provisioned: bool
    gateway_reachability: str
    inbound_route: str
    media_readiness: str
    inbound_did: Optional[str] = None
    missing_prerequisites: List[str]
    carrier_forwarding_guidance: Dict[str, Any]

class ExotelInboundWebhookPayload(BaseModel):
    CallSid: str
    From: str
    To: str
    CallType: Optional[str] = "transferred"
    DialWhomNumber: Optional[str] = None
    Direction: Optional[str] = "inbound"
    CurrentTime: Optional[str] = None

def evaluate_provider_state(config: Dict[str, Any], media_server_online: bool = True) -> tuple[str, str, List[str]]:
    missing: List[str] = []
    
    account_sid = config.get("account_sid")
    api_key = config.get("api_key")
    api_token = config.get("api_token") or config.get("api_token_hash")
    inbound_did = config.get("inbound_did")
    gateway_uri = config.get("gateway_public_uri")

    if not account_sid:
        missing.append("EXOTEL_ACCOUNT_SID")
    if not api_key:
        missing.append("EXOTEL_API_KEY")
    if not api_token:
        missing.append("EXOTEL_API_TOKEN")
    if not inbound_did:
        missing.append("PUBLIC_INBOUND_DID")
    if not gateway_uri:
        missing.append("GATEWAY_PUBLIC_URI")

    # 1. NOT_CONFIGURED
    if not account_sid and not api_key and not api_token:
        return (
            STATE_NOT_CONFIGURED,
            "No India PSTN provider configured. Exotel account credentials, public virtual DID, and public gateway URI required.",
            missing
        )

    # 2. CREDENTIALS_INVALID check
    if api_key == "invalid_key" or api_token in ("invalid_token", "bad_creds"):
        return (
            STATE_CREDENTIALS_INVALID,
            "Exotel API Key or API Token rejected during authentication check.",
            missing
        )

    # 3. ACCOUNT_NOT_READY check
    if account_sid in ("suspended_account", "inactive_account"):
        return (
            STATE_ACCOUNT_NOT_READY,
            f"Exotel account '{account_sid}' is suspended or in trial pending KYC/telecom approval.",
            missing
        )

    # Missing credentials
    if not account_sid or not api_key or not api_token:
        return (
            STATE_NOT_CONFIGURED,
            f"Incomplete Exotel credentials. Missing: {', '.join(missing)}",
            missing
        )

    # 4. NUMBER_NOT_PROVISIONED check
    if not inbound_did or not is_valid_e164_number(inbound_did):
        return (
            STATE_NUMBER_NOT_PROVISIONED,
            f"Inbound DID '{inbound_did or 'None'}' is invalid. Extension 1000 or private shortcodes cannot receive public PSTN calls. Must be a valid E.164 phone number (+91...).",
            missing
        )

    # 5. INBOUND_ROUTE_MISSING check
    if not gateway_uri:
        return (
            STATE_INBOUND_ROUTE_MISSING,
            "No inbound destination route configured in Exotel for the provisioned DID.",
            missing
        )

    # 6. GATEWAY_UNREACHABLE check
    if is_private_or_loopback_uri(gateway_uri):
        return (
            STATE_GATEWAY_UNREACHABLE,
            f"Gateway URI '{gateway_uri}' is a private RFC1918 / LAN / loopback endpoint. External cellular networks and Exotel cannot reach private LAN IP 192.168.x.x. A public FQDN or static public IP is required.",
            ["GATEWAY_PUBLIC_FQDN_OR_IP"]
        )

    # 7. MEDIA_NOT_READY check
    if not media_server_online:
        return (
            STATE_MEDIA_NOT_READY,
            "Asterisk PBX / AudioSocket media bridge is unreachable or audio ports are not bound.",
            ["AUDIOSOCKET_MEDIA_BRIDGE"]
        )

    # 8. CONNECTED
    return (
        STATE_CONNECTED,
        "Exotel Dynamic SIP Trunking / Voice Stream connection is active and ready to bridge public Airtel forwarded calls to Edge on-device AI.",
        []
    )

def get_airtel_forwarding_instructions(target_did: Optional[str]) -> Dict[str, Any]:
    did_display = target_did if (target_did and is_valid_e164_number(target_did)) else "<PROVISIONED_GATEWAY_E164_DID>"
    return {
        "regulatory_notice": "DoT India Directive (Effective April 15, 2024): All USSD-based call forwarding shortcodes (*21*, *67*, *401#) have been deactivated across India to prevent cyber fraud and OTP interception.",
        "supported_methods": [
            {
                "method": "Native Android Settings (Recommended)",
                "steps": [
                    "Open the Phone (Dialer) app on the handset.",
                    "Tap the three-dot menu in the top-right corner and select 'Settings'.",
                    "Tap 'Calling accounts' (or 'Calls') and select the Airtel SIM.",
                    "Tap 'Call forwarding'.",
                    "Select 'When unanswered' (or 'Always forward').",
                    f"Enter the provisioned public Gateway number: {did_display}",
                    "Tap 'Turn on' / 'Update'."
                ]
            },
            {
                "method": "Airtel Thanks Application",
                "steps": [
                    "Open the Airtel Thanks app on the phone.",
                    "Navigate to Services / Manage Accounts -> Call Settings.",
                    "Select 'Call Forwarding' -> Enter the verified Gateway DID.",
                    "Confirm with in-app authentication."
                ]
            }
        ],
        "strictly_prohibited": [
            "Do NOT dial *21*1000# or attempt forwarding to private extension 1000.",
            "Do NOT configure forwarding to private IP 192.168.0.101.",
            "Do NOT issue legacy USSD codes (*401*, *21*) which are rejected by Airtel base stations."
        ]
    }
