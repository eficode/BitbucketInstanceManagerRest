terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "4.34.0"
    }
  }
}

provider "aws" {

  region              = "eu-central-1"
  access_key          = var.aws_credentials.access_key
  secret_key          = var.aws_credentials.secret_key
  allowed_account_ids = [var.aws_credentials.account]

  skip_get_ec2_platforms      = true
  skip_metadata_api_check     = true
  skip_region_validation      = true
  skip_credentials_validation = true

  default_tags {
    tags = var.tags
  }

}


data "aws_region" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

/*
Get the NIC of the LB in the public net
*/
data "aws_network_interface" "lb_nic" {


  filter {
    name   = "description"
    values = ["ELB ${aws_lb.load-balancer.arn_suffix}"]
  }



  filter {
    name   = "subnet-id"
    values = [aws_subnet.base-stack-public-subnet[0].id]
  }

}

output "SSH-TO-Node" {
  value = "If you wnat to SSH to the Docker engine node:\nssh -v ${var.ec2-username}@${aws_lb.load-balancer.dns_name} -p 22 -o StrictHostKeyChecking=no"
}

output "Hosts-record" {
  value = "Add this record to your systems hosts file:\n${data.aws_network_interface.lb_nic.association[0].public_ip} bitbucket.domain.se jira.domain.se docker.domain.se"

}