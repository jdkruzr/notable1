import json
import os
import boto3
import uuid
import requests
from datetime import datetime

def lambda_handler(event, context):
    # Initialize DynamoDB client
    dynamodb = boto3.resource('dynamodb')
    table = dynamodb.Table('CalendarEvents')
    try:
        # Extract user text from the event
        user_text = event.get('text', '')

        # Generate a unique event ID
        event_id = str(uuid.uuid4())
        
        # Get user ID from the event or use a default
        user_id = event.get('userId', 'anonymous')
        
        if not user_text:
            return {
                'statusCode': 400,
                'body': json.dumps({'error': 'No text provided'})
            }
        
        # Record the timestamp when the request was received
        request_time = datetime.utcnow().isoformat()
        
        # Get calendar event from LLM
        calendar_event = extract_calendar_event(user_text)
        
        # Save both input and output to DynamoDB
        save_to_dynamodb(table, user_id, event_id, user_text, calendar_event, request_time)
        
        return {
            'statusCode': 200,
            'body': json.dumps(calendar_event)
        }
        
    except Exception as e:
        return {
            'statusCode': 500,
            'body': json.dumps({'error': str(e)})
        }

def save_to_dynamodb(table, user_id, event_id, input_text, calendar_event, request_time):
    """Save the input and output to DynamoDB"""
    
    # Extract start_time for the GSI
    start_time = calendar_event.get('start_time', '')
    
    # Create the item to store
    item = {
        'UserId': user_id,
        'EventId': event_id,
        'InputText': input_text,
        'CalendarEvent': calendar_event,
        'RequestTime': request_time,
        'StartTime': start_time
    }
    
    # Add optional fields if they exist
    for optional_field in ['title', 'end_time', 'location', 'description', 'attendees']:
        if optional_field in calendar_event and calendar_event[optional_field]:
            item[optional_field] = calendar_event[optional_field]
    
    # Put the item into DynamoDB
    table.put_item(Item=item)
    
    return True

def extract_calendar_event(text):
    # Define which LLM service to use
    llm_service = os.environ.get('LLM_SERVICE', 'openai')  # 'openai', or 'custom'
    
    if llm_service == 'openai':
        return extract_with_openai(text)
    elif llm_service == 'custom':
        return extract_with_custom_endpoint(text)
    else:
        raise ValueError(f"Unsupported LLM service: {llm_service}")


def extract_with_openai(text):
    import json
    import requests
    from datetime import datetime
    
    # Get OpenAI API key from environment variables
    api_key = os.environ.get('OPENAI_API_KEY')
    if not api_key:
        raise ValueError("OPENAI_API_KEY environment variable not set")
    
    # Get model from environment variables or use default
    model = os.environ.get('OPENAI_MODEL', 'gpt-4')
    
    # Define the system message to explain the task
    system_message = """
    Extract calendar event details from the user's text and return a JSON object with the following properties:
    - title: the event title
    - start_time: ISO format datetime for the start of the event
    - end_time: ISO format datetime for the end of the event
    - location: the event location (if provided)
    - description: any details about the event (if provided)
    - attendees: array of attendees (if provided)
    
    If any information is missing, make a reasonable assumption based on context or leave as null.
    """
    
    # Prepare the request
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}"
    }
    
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_message},
            {"role": "user", "content": text}
        ],
        "temperature": 0.1,  # Low temperature for more deterministic output
    }
    
    # Use a session to reuse connection resources
    with requests.Session() as session:
        # Make the API call to OpenAI
        response = session.post(
            "https://api.openai.com/v1/chat/completions",
            headers=headers,
            json=payload,
            timeout=25  # Set a timeout to avoid Lambda waiting too long
        )
    
    # Check for successful response
    if response.status_code != 200:
        raise ValueError(f"Error from OpenAI API: {response.text}")
    
    # Parse response
    response_data = response.json()
    result_text = response_data['choices'][0]['message']['content']
    
    # Convert string to JSON
    try:
        result = json.loads(result_text)
    except json.JSONDecodeError:
        # If the response is not valid JSON, extract JSON portion
        import re
        json_match = re.search(r'(\{.*\})', result_text, re.DOTALL)
        if json_match:
            try:
                result = json.loads(json_match.group(1))
            except:
                raise ValueError("Could not parse JSON from response")
        else:
            raise ValueError("Response did not contain valid JSON")
    
    # Validate and return the calendar event
    validate_calendar_event(result)
    return result


def extract_with_custom_endpoint(text):
    # Get custom endpoint URL from environment variables
    endpoint_url = os.environ.get('CUSTOM_LLM_ENDPOINT')
    api_key = os.environ.get('CUSTOM_LLM_API_KEY')
    
    if not endpoint_url:
        raise ValueError("CUSTOM_LLM_ENDPOINT environment variable not set")
    
    headers = {
        'Content-Type': 'application/json'
    }
    
    if api_key:
        headers['Authorization'] = f'Bearer {api_key}'
    
    # Create payload similar to OpenAI format for compatibility
    payload = {
        "model": os.environ.get('CUSTOM_LLM_MODEL', 'default'),
        "messages": [
            {
                "role": "system", 
                "content": """
                Extract calendar event details from the user's text and return a JSON object with the following properties:
                - title: the event title
                - start_time: ISO format datetime for the start of the event
                - end_time: ISO format datetime for the end of the event  
                - location: the event location (if provided)
                - description: any details about the event (if provided)
                - attendees: array of attendees (if provided)
                
                If any information is missing, make a reasonable assumption based on context or leave as null.
                Return only valid JSON.
                """
            },
            {
                "role": "user",
                "content": text
            }
        ],
        "temperature": 0.1
    }
    
    response = requests.post(endpoint_url, headers=headers, json=payload)
    
    if response.status_code != 200:
        raise ValueError(f"Error from LLM API: {response.text}")
    
    response_data = response.json()
    
    # Extract the generated calendar event (adapt this based on your API's response format)
    if "choices" in response_data and len(response_data["choices"]) > 0:
        # OpenAI-like response format
        result_text = response_data["choices"][0]["message"]["content"]
    else:
        # Custom format - adjust as needed
        result_text = response_data.get("output", response_data.get("response", ""))
    
    # Parse the result into JSON
    try:
        result = json.loads(result_text)
    except json.JSONDecodeError:
        # If the response is not valid JSON, try to extract JSON portion
        import re
        json_match = re.search(r'(\{.*\})', result_text, re.DOTALL)
        if json_match:
            try:
                result = json.loads(json_match.group(1))
            except:
                raise ValueError("Could not parse JSON from response")
        else:
            raise ValueError("Response did not contain valid JSON")
    
    # Validate and return the calendar event
    validate_calendar_event(result)
    return result

def validate_calendar_event(event):
    """Basic validation of calendar event structure"""
    required_fields = ['title', 'start_time']
    for field in required_fields:
        if field not in event or not event[field]:
            raise ValueError(f"Missing required field: {field}")
    
    # Validate date formats
    try:
        if event.get('start_time'):
            datetime.fromisoformat(event['start_time'].replace('Z', '+00:00'))
        if event.get('end_time'):
            datetime.fromisoformat(event['end_time'].replace('Z', '+00:00'))
    except ValueError as e:
        raise ValueError(f"Invalid datetime format: {str(e)}")
    
    return True