variable "tags" {
  description = "Tags to set on resources."
  type        = map(string)
  default = {
    owner : "USER"
    testing : "True"
    okToRemove : "True"
    useCase : "bb"
  }
}

variable "aws_credentials" {

  type = map(string)

  default = {
    "access_key" = ""
    "secret_key" = ""
    "account" = ""
  }

}




variable "trusted-external-ips" {
  description = "These IPs will have acces to the exposed ports"
  type = set(string)
  default = ["1.2.3.4/32"]

}


variable "dockerServerCert" {
  type = map(string)
  default = {
    "tlscacert" = "../docker/dockerCert/ca.pem"
    "tlscert" = "../docker/dockerCert/server-cert.pem"
    "tlskey" = "../docker/dockerCert/server-key.pem"
  }

}

variable "ssh-public-key-local-path" {
  type    = string
  default = "~/.ssh/id_rsa.pub"
}

variable "ec2-username" {
  type    = string
  default = "ubuntu"
}

variable "ingress_rules_from_trusted" {
  description = "This will expose the corresponding ports to the internet, but limited to trusted-external-ips"
  type = list(object({
    port   = number
    protocol    = string
    description = string
  }))
  default     = [

    {
      port   =  8080
      protocol    = "tcp"
      description = "JIRA"
    },
    {
      port   =  22
      protocol    = "tcp"
      description = "SSH"
    },
    {
      port   =  7990
      protocol    = "tcp"
      description = "Bitbucket"
    },
    {
      port   =  80
      protocol    = "tcp"
      description = "HTTP"
    },
    {
      port   =  2376
      protocol    = "tcp"
      description = "Docker port"
    }
  ]
}

